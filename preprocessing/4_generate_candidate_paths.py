#!/usr/bin/env python3
"""Generate realistic candidate routes for the Porto Java sweep simulator.

Input OD CSV (node IDs):
    od_id,origin_node,destination_node

The Porto preprocessing stable-route CSV is accepted directly as well:
    od_id,sample_start_node,sample_end_node,...

Coordinates are also accepted:
    od_id,origin_lon,origin_lat,destination_lon,destination_lat

The output route and waypoint CSVs can be used directly as `candidateRoutes`
and `routeWaypoints` in sweep.properties.

Requires: networkx
Optional: pyproj (more accurate coordinate distances for projected graphs)
"""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
import hashlib
import heapq
import math
import os
import random
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

try:
    import networkx as nx
except ModuleNotFoundError as exc:
    raise SystemExit(
        "Missing dependency 'networkx'. Install it with: python -m pip install networkx"
    ) from exc


SEQUENCE_SEPARATOR = ";"


ROAD_CLASS_FACTOR = {
    "motorway": 0.45,
    "motorway_link": 0.55,
    "trunk": 0.55,
    "trunk_link": 0.65,
    "primary": 0.70,
    "primary_link": 0.78,
    "secondary": 0.82,
    "secondary_link": 0.88,
    "tertiary": 0.92,
    "tertiary_link": 0.96,
    "residential": 1.08,
    "living_street": 1.22,
    "service": 1.35,
    "unclassified": 1.10,
}


@dataclass(frozen=True)
class EdgeRef:
    u: str
    v: str
    key: str
    edge_id: str
    length_m: float
    travel_sec: float
    highway: str
    name: str
    ref: str
    osmids: tuple[str, ...]
    departure_heading: float
    arrival_heading: float


@dataclass
class Candidate:
    nodes: list[str]
    edges: list[EdgeRef]
    family: str

    @property
    def distance_m(self) -> float:
        return sum(e.length_m for e in self.edges)

    @property
    def travel_sec(self) -> float:
        return sum(e.travel_sec for e in self.edges)

    @property
    def signature(self) -> tuple[str, ...]:
        return tuple(e.edge_id for e in self.edges)


# Process workers initialize these once and then reuse the graph for many ODs.
_WORKER_GRAPH: nx.DiGraph | None = None
_WORKER_LOOKUP: dict[tuple[str, str], EdgeRef] | None = None


def scalar(value: Any, default: Any = "") -> Any:
    if isinstance(value, (list, tuple)):
        return value[0] if value else default
    return default if value is None else value


def number(value: Any, default: float) -> float:
    try:
        result = float(scalar(value, default))
        return result if math.isfinite(result) else default
    except (TypeError, ValueError):
        return default


def highway_name(value: Any) -> str:
    return str(scalar(value, "unclassified")).strip().lower()


def normalized_text(value: Any) -> str:
    return " ".join(str(scalar(value, "")).strip().lower().replace("_", " ").split())


def osmid_values(value: Any) -> tuple[str, ...]:
    if value is None:
        return ()
    if isinstance(value, (list, tuple, set)):
        return tuple(sorted({str(v).strip() for v in value if str(v).strip()}))
    return tuple(re.findall(r"\d+", str(value)))


def geometry_points(value: Any) -> list[tuple[float, float]]:
    match = re.search(
        r"LINESTRING\s*(?:Z\s*)?\((.+)\)", str(scalar(value, "")), re.IGNORECASE
    )
    if not match:
        return []
    points: list[tuple[float, float]] = []
    for token in match.group(1).split(","):
        values = token.strip().split()
        if len(values) < 2:
            return []
        try:
            points.append((float(values[0]), float(values[1])))
        except ValueError:
            return []
    return points


def heading(a: tuple[float, float], b: tuple[float, float]) -> float:
    dx, dy = b[0] - a[0], b[1] - a[1]
    # Correct longitude scale for ordinary unprojected lon/lat coordinates.
    if all(abs(x) <= 180.0 for x in (a[0], b[0])) and all(
        abs(y) <= 90.0 for y in (a[1], b[1])
    ):
        dx *= math.cos(math.radians((a[1] + b[1]) * 0.5))
    return math.degrees(math.atan2(dy, dx))


def squared_distance(a: tuple[float, float], b: tuple[float, float]) -> float:
    return (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2


def speed_kph(data: dict[str, Any]) -> float:
    speed = scalar(data.get("speed_kph"))
    if speed not in (None, ""):
        return max(5.0, number(speed, 30.0))
    raw = str(scalar(data.get("maxspeed"), "")).lower()
    digits = "".join(ch if ch.isdigit() or ch == "." else " " for ch in raw).split()
    if digits:
        value = number(digits[0], 30.0)
        return max(5.0, value * 1.609344 if "mph" in raw else value)
    defaults = {
        "motorway": 90, "trunk": 70, "primary": 55, "secondary": 45,
        "tertiary": 40, "residential": 30, "living_street": 15, "service": 20,
    }
    return float(defaults.get(highway_name(data.get("highway")), 30))


def edge_values(
    u: Any, v: Any, key: Any, data: dict[str, Any],
    u_xy: tuple[float, float] | None, v_xy: tuple[float, float] | None,
) -> EdgeRef:
    u_s, v_s, key_s = str(u), str(v), str(key)
    length = max(0.1, number(data.get("length"), 1.0))
    travel = number(data.get("travel_time"), -1.0)
    if travel <= 0:
        travel = max(0.1, length / (speed_kph(data) / 3.6))
    edge_id = str(scalar(data.get("edge_id"), "")).strip() or f"{u_s}|{v_s}|{key_s}"
    points = geometry_points(data.get("geometry"))
    if len(points) >= 2 and u_xy is not None:
        if squared_distance(points[-1], u_xy) < squared_distance(points[0], u_xy):
            points.reverse()
    if len(points) < 2 and u_xy is not None and v_xy is not None:
        points = [u_xy, v_xy]
    departure = heading(points[0], points[1]) if len(points) >= 2 else math.nan
    arrival = heading(points[-2], points[-1]) if len(points) >= 2 else math.nan
    return EdgeRef(
        u_s, v_s, key_s, edge_id, length, travel,
        highway_name(data.get("highway")), normalized_text(data.get("name")),
        normalized_text(data.get("ref")), osmid_values(data.get("osmid")),
        departure, arrival,
    )


def build_search_graph(graph: nx.Graph) -> tuple[nx.DiGraph, dict[tuple[str, str], EdgeRef]]:
    """Collapse parallel edges by fastest edge while retaining its stable identity."""
    search = nx.DiGraph()
    lookup: dict[tuple[str, str], EdgeRef] = {}
    for node, data in graph.nodes(data=True):
        search.add_node(str(node), **data)
    raw_edges = graph.edges(keys=True, data=True) if graph.is_multigraph() else (
        (u, v, "0", d) for u, v, d in graph.edges(data=True)
    )
    for u, v, key, data in raw_edges:
        u_data, v_data = graph.nodes[u], graph.nodes[v]
        ux, uy = number(u_data.get("x"), math.nan), number(u_data.get("y"), math.nan)
        vx, vy = number(v_data.get("x"), math.nan), number(v_data.get("y"), math.nan)
        u_xy = (ux, uy) if math.isfinite(ux) and math.isfinite(uy) else None
        v_xy = (vx, vy) if math.isfinite(vx) and math.isfinite(vy) else None
        edge = edge_values(u, v, key, data, u_xy, v_xy)
        pair = (edge.u, edge.v)
        current = lookup.get(pair)
        if current is None or edge.travel_sec < current.travel_sec:
            lookup[pair] = edge
            search.add_edge(
                edge.u, edge.v, time=edge.travel_sec, length=edge.length_m,
                hierarchy=ROAD_CLASS_FACTOR.get(edge.highway, 1.10),
            )
    return search, lookup


def candidate_from_nodes(
    nodes: Iterable[Any], lookup: dict[tuple[str, str], EdgeRef], family: str
) -> Candidate:
    seq = [str(n) for n in nodes]
    edges = [lookup[(seq[i], seq[i + 1])] for i in range(len(seq) - 1)]
    return Candidate(seq, edges, family)


def shortest(
    graph: nx.DiGraph,
    lookup: dict[tuple[str, str], EdgeRef],
    origin: str,
    destination: str,
    weight: str | Any,
    family: str,
) -> Candidate | None:
    try:
        nodes = nx.shortest_path(graph, origin, destination, weight=weight)
        return candidate_from_nodes(nodes, lookup, family)
    except (nx.NetworkXNoPath, nx.NodeNotFound, KeyError):
        return None


def weighted_overlap(a: Candidate, b: Candidate) -> float:
    a_map = {e.edge_id: e.length_m for e in a.edges}
    b_map = {e.edge_id: e.length_m for e in b.edges}
    union = set(a_map) | set(b_map)
    if not union:
        return 1.0
    shared = sum(min(a_map[e], b_map[e]) for e in set(a_map) & set(b_map))
    total = sum(max(a_map.get(e, 0.0), b_map.get(e, 0.0)) for e in union)
    return shared / total if total else 0.0


def add_if_valid(
    accepted: list[Candidate],
    candidate: Candidate | None,
    fastest_sec: float,
    max_stretch: float,
    max_overlap: float,
) -> bool:
    if candidate is None or not candidate.edges or candidate.signature in {c.signature for c in accepted}:
        return False
    if candidate.travel_sec > fastest_sec * max_stretch:
        return False
    if accepted and max(weighted_overlap(candidate, c) for c in accepted) > max_overlap:
        return False
    accepted.append(candidate)
    return True


def penalty_candidates(
    graph: nx.DiGraph,
    lookup: dict[tuple[str, str], EdgeRef],
    origin: str,
    destination: str,
    count: int,
    penalty: float,
) -> list[Candidate]:
    usage: dict[tuple[str, str], int] = {}
    result: list[Candidate] = []
    for _ in range(max(0, count * 4)):
        def cost(u: str, v: str, data: dict[str, Any]) -> float:
            used = usage.get((u, v), 0)
            # Minor streets receive a slightly larger base cost and used edges
            # receive progressively larger penalties.
            return float(data["time"]) * float(data["hierarchy"]) * (1.0 + penalty * used)

        path = shortest(graph, lookup, origin, destination, cost, "penalty")
        if path is None:
            break
        if path.signature not in {c.signature for c in result}:
            result.append(path)
        for edge in path.edges:
            usage[(edge.u, edge.v)] = usage.get((edge.u, edge.v), 0) + 1
        if len(result) >= count:
            break
    return result


def highway_family(value: str) -> str:
    return value.removesuffix("_link")


def turn_angle(incoming: EdgeRef, outgoing: EdgeRef) -> float:
    if not math.isfinite(incoming.arrival_heading) or not math.isfinite(outgoing.departure_heading):
        return 0.0
    return (outgoing.departure_heading - incoming.arrival_heading + 180.0) % 360.0 - 180.0


def same_corridor(incoming: EdgeRef, outgoing: EdgeRef, abs_angle: float) -> bool:
    same_ref = bool(incoming.ref and incoming.ref == outgoing.ref)
    same_name = bool(incoming.name and incoming.name == outgoing.name)
    shared_osmid = bool(set(incoming.osmids) & set(outgoing.osmids))
    compatible_class = highway_family(incoming.highway) == highway_family(outgoing.highway)
    return (
        ((same_ref or same_name) and abs_angle <= 60.0)
        or (shared_osmid and abs_angle <= 75.0)
        or (not incoming.name and not outgoing.name and compatible_class and abs_angle <= 20.0)
    )


def transition_penalty(incoming: EdgeRef, outgoing: EdgeRef, scale: float) -> float:
    abs_angle = abs(turn_angle(incoming, outgoing))
    if abs_angle < 20.0:
        angle_cost = 0.0
    elif abs_angle < 45.0:
        angle_cost = 2.0
    elif abs_angle < 120.0:
        angle_cost = 8.0
    elif abs_angle < 165.0:
        angle_cost = 15.0
    else:
        angle_cost = 60.0

    if same_corridor(incoming, outgoing, abs_angle):
        angle_cost *= 0.25
        corridor_change = 0.0
    else:
        corridor_change = 5.0
    class_change = (
        0.0
        if highway_family(incoming.highway) == highway_family(outgoing.highway)
        else 4.0
    )
    return scale * (angle_cost + corridor_change + class_change)


def corridor_shortest(
    graph: nx.DiGraph,
    lookup: dict[tuple[str, str], EdgeRef],
    origin: str,
    destination: str,
    transition_scale: float,
    used_edges: dict[str, int],
    reuse_penalty: float,
) -> Candidate | None:
    """Dijkstra over incoming-edge states, allowing real transition costs."""
    if origin not in graph or destination not in graph:
        return None
    start = ("", origin)
    distance: dict[tuple[str, str], float] = {start: 0.0}
    previous: dict[tuple[str, str], tuple[str, str]] = {}
    queue: list[tuple[float, str, str]] = [(0.0, "", origin)]
    destination_state: tuple[str, str] | None = None

    while queue:
        cost, prev_node, node = heapq.heappop(queue)
        state = (prev_node, node)
        if cost > distance.get(state, math.inf):
            continue
        if node == destination:
            destination_state = state
            break
        incoming = lookup.get((prev_node, node)) if prev_node else None
        for next_node in graph.successors(node):
            outgoing = lookup[(node, next_node)]
            movement_cost = (
                transition_penalty(incoming, outgoing, transition_scale)
                if incoming is not None else 0.0
            )
            diversity_cost = (
                outgoing.travel_sec * reuse_penalty
                * used_edges.get(outgoing.edge_id, 0)
            )
            new_cost = cost + outgoing.travel_sec + movement_cost + diversity_cost
            next_state = (node, next_node)
            if new_cost < distance.get(next_state, math.inf):
                distance[next_state] = new_cost
                previous[next_state] = state
                heapq.heappush(queue, (new_cost, node, next_node))

    if destination_state is None:
        return None
    states = [destination_state]
    while states[-1] != start:
        states.append(previous[states[-1]])
    states.reverse()
    nodes = [origin] + [state[1] for state in states[1:]]
    return candidate_from_nodes(nodes, lookup, "corridor")


def corridor_candidates(
    graph: nx.DiGraph,
    lookup: dict[tuple[str, str], EdgeRef],
    origin: str,
    destination: str,
    count: int,
    transition_scale: float,
    reuse_penalty: float,
) -> list[Candidate]:
    result: list[Candidate] = []
    used_edges: dict[str, int] = {}
    for _ in range(max(0, count)):
        path = corridor_shortest(
            graph, lookup, origin, destination, transition_scale,
            used_edges, reuse_penalty,
        )
        if path is None:
            break
        if path.signature not in {candidate.signature for candidate in result}:
            result.append(path)
        for edge in path.edges:
            used_edges[edge.edge_id] = used_edges.get(edge.edge_id, 0) + 1
    return result


def stochastic_candidates(
    graph: nx.DiGraph,
    lookup: dict[tuple[str, str], EdgeRef],
    origin: str,
    destination: str,
    count: int,
    sigma: float,
    rng: random.Random,
) -> list[Candidate]:
    result: list[Candidate] = []
    for _ in range(max(0, count * 8)):
        class_noise: dict[str, float] = {}
        edge_noise: dict[tuple[str, str], float] = {}
        for u, v in graph.edges:
            edge = lookup[(u, v)]
            if edge.highway not in class_noise:
                class_noise[edge.highway] = rng.gauss(-0.5 * sigma * sigma, sigma * 0.65)
            local = rng.gauss(-0.5 * sigma * sigma, sigma * 0.35)
            edge_noise[(u, v)] = math.exp(class_noise[edge.highway] + local)

        def cost(u: str, v: str, data: dict[str, Any]) -> float:
            return float(data["time"]) * edge_noise[(u, v)]

        path = shortest(graph, lookup, origin, destination, cost, "stochastic")
        if path and path.signature not in {c.signature for c in result}:
            result.append(path)
        if len(result) >= count:
            break
    return result


def via_candidates(
    graph: nx.DiGraph,
    lookup: dict[tuple[str, str], EdgeRef],
    origin: str,
    destination: str,
    count: int,
    rng: random.Random,
) -> list[Candidate]:
    # Sampling reachable graph nodes keeps this practical without requiring geometry.
    nodes = [str(n) for n in graph.nodes if str(n) not in {origin, destination}]
    rng.shuffle(nodes)
    result: list[Candidate] = []
    for via in nodes[: min(len(nodes), max(50, count * 30))]:
        left = shortest(graph, lookup, origin, via, "time", "via")
        right = shortest(graph, lookup, via, destination, "time", "via")
        if left is None or right is None:
            continue
        joined_nodes = left.nodes + right.nodes[1:]
        # Reject loops: locally plausible alternatives should remain simple.
        if len(set(joined_nodes)) != len(joined_nodes):
            continue
        path = Candidate(joined_nodes, left.edges + right.edges, "via")
        if path.signature not in {c.signature for c in result}:
            result.append(path)
        if len(result) >= count:
            break
    return result


def nearest_node(graph: nx.DiGraph, lon: float, lat: float) -> str:
    best_node, best_dist = "", math.inf
    for node, data in graph.nodes(data=True):
        x, y = number(data.get("x"), math.nan), number(data.get("y"), math.nan)
        if not math.isfinite(x) or not math.isfinite(y):
            continue
        # Adequate for nearest-node selection within one city.
        adjusted_dx = (x - lon) * math.cos(math.radians(lat))
        dist = adjusted_dx * adjusted_dx + (y - lat) * (y - lat)
        if dist < best_dist:
            best_node, best_dist = str(node), dist
    if not best_node:
        raise ValueError("Graph nodes have no usable x/y coordinates")
    return best_node


def resolve_od(row: dict[str, str], graph: nx.DiGraph) -> tuple[str, str, str]:
    od_id = (row.get("od_id") or "").strip()
    if not od_id:
        raise ValueError("OD row is missing od_id")
    origin = (
        row.get("origin_node")
        or row.get("sample_start_node")
        or row.get("start_node")
        or ""
    ).strip()
    destination = (
        row.get("destination_node")
        or row.get("sample_end_node")
        or row.get("end_node")
        or ""
    ).strip()
    if not origin:
        try:
            origin = nearest_node(graph, float(row["origin_lon"]), float(row["origin_lat"]))
        except (KeyError, TypeError, ValueError) as exc:
            raise ValueError(
                f"OD {od_id} has no origin_node/sample_start_node or origin coordinates"
            ) from exc
    if not destination:
        try:
            destination = nearest_node(
                graph, float(row["destination_lon"]), float(row["destination_lat"])
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise ValueError(
                f"OD {od_id} has no destination_node/sample_end_node or destination coordinates"
            ) from exc
    return od_id, origin, destination


def stable_seed(seed: int, od_id: str) -> int:
    digest = hashlib.sha256(f"{seed}:{od_id}".encode()).digest()
    return int.from_bytes(digest[:8], "big")


def generate_for_od(
    graph: nx.DiGraph,
    lookup: dict[tuple[str, str], EdgeRef],
    od_id: str,
    origin: str,
    destination: str,
    args: argparse.Namespace,
) -> list[Candidate]:
    fastest = shortest(graph, lookup, origin, destination, "time", "fastest")
    if fastest is None:
        return []
    rng = random.Random(stable_seed(args.seed, od_id))
    pool: list[Candidate] = [
        fastest,
        *filter(None, [shortest(graph, lookup, origin, destination, "length", "shortest")]),
        *corridor_candidates(
            graph, lookup, origin, destination, args.corridor_count,
            args.turn_penalty_scale, args.corridor_reuse_penalty,
        ),
        *penalty_candidates(graph, lookup, origin, destination, args.penalty_count, args.penalty),
        *stochastic_candidates(
            graph, lookup, origin, destination, args.stochastic_count, args.sigma, rng
        ),
        *via_candidates(graph, lookup, origin, destination, args.via_count, rng),
    ]
    # Prefer anchors, then low-stretch members of each family.
    family_order = {
        "fastest": 0, "shortest": 1, "corridor": 2,
        "penalty": 3, "stochastic": 4, "via": 5,
    }
    pool.sort(key=lambda c: (family_order[c.family], c.travel_sec, c.distance_m))
    accepted: list[Candidate] = []
    for candidate in pool:
        overlap_limit = 1.0 if candidate.family in {"fastest", "shortest"} else args.max_overlap
        add_if_valid(accepted, candidate, fastest.travel_sec, args.max_stretch, overlap_limit)
        if len(accepted) >= args.max_candidates:
            break
    return accepted


def initialize_worker(graph_path: str) -> None:
    global _WORKER_GRAPH, _WORKER_LOOKUP
    raw_graph = nx.read_graphml(graph_path)
    _WORKER_GRAPH, _WORKER_LOOKUP = build_search_graph(raw_graph)


def generate_row(
    task: tuple[dict[str, str], argparse.Namespace]
) -> tuple[str, list[Candidate], str | None]:
    """Generate one OD inside a worker and return a printable warning if needed."""
    row, args = task
    if _WORKER_GRAPH is None or _WORKER_LOOKUP is None:
        raise RuntimeError("Candidate-generation worker was not initialized")
    od_id, origin, destination = resolve_od(row, _WORKER_GRAPH)
    candidates = generate_for_od(
        _WORKER_GRAPH, _WORKER_LOOKUP, od_id, origin, destination, args
    )
    warning = None
    if not candidates:
        warning = f"WARN {od_id}: no directed path {origin} -> {destination}"
    return od_id, candidates, warning


def write_outputs(
    routes_path: Path,
    waypoints_path: Path,
    graph: nx.DiGraph,
    generated: list[tuple[str, Candidate]],
) -> None:
    routes_path.parent.mkdir(parents=True, exist_ok=True)
    waypoints_path.parent.mkdir(parents=True, exist_ok=True)
    with routes_path.open("w", newline="", encoding="utf-8") as route_file, \
            waypoints_path.open("w", newline="", encoding="utf-8") as waypoint_file:
        route_writer = csv.writer(route_file)
        waypoint_writer = csv.writer(waypoint_file)
        route_writer.writerow([
            "od_id", "route_id", "route_rank", "generator", "stable_share_norm",
            "avg_time_sec", "avg_distance_m", "route_node_seq", "edge_seq",
        ])
        waypoint_writer.writerow(["route_id", "seq", "node_id", "lon", "lat"])
        ranks: dict[str, int] = {}
        for od_id, candidate in generated:
            ranks[od_id] = ranks.get(od_id, 0) + 1
            rank = ranks[od_id]
            route_id = f"{od_id}__cand_{rank:02d}_{candidate.family}"
            route_writer.writerow([
                od_id, route_id, rank, candidate.family, 0.0,
                f"{candidate.travel_sec:.6f}", f"{candidate.distance_m:.6f}",
                SEQUENCE_SEPARATOR.join(candidate.nodes),
                SEQUENCE_SEPARATOR.join(e.edge_id for e in candidate.edges),
            ])
            for seq, node in enumerate(candidate.nodes):
                data = graph.nodes[node]
                waypoint_writer.writerow([
                    route_id, seq, node, data.get("x", ""), data.get("y", ""),
                ])


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--graph", required=True, type=Path, help="OSMnx/NetworkX GraphML")
    parser.add_argument("--ods", required=True, type=Path, help="OD-pair CSV")
    parser.add_argument("--routes-out", required=True, type=Path)
    parser.add_argument("--waypoints-out", required=True, type=Path)
    parser.add_argument("--max-candidates", type=int, default=15)
    parser.add_argument("--penalty-count", type=int, default=6)
    parser.add_argument("--corridor-count", type=int, default=3)
    parser.add_argument("--stochastic-count", type=int, default=6)
    parser.add_argument("--via-count", type=int, default=5)
    parser.add_argument("--penalty", type=float, default=0.65)
    parser.add_argument(
        "--turn-penalty-scale", type=float, default=1.0,
        help="Multiplier for turn and corridor-change penalties",
    )
    parser.add_argument(
        "--corridor-reuse-penalty", type=float, default=0.35,
        help="Edge-reuse penalty for diversifying corridor candidates",
    )
    parser.add_argument("--sigma", type=float, default=0.25)
    parser.add_argument("--max-stretch", type=float, default=1.35)
    parser.add_argument("--max-overlap", type=float, default=0.85)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--workers",
        type=int,
        default=max(1, (os.cpu_count() or 2) - 1),
        help="Parallel OD worker processes (default: logical CPUs minus one)",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if (
        args.max_candidates < 1
        or args.corridor_count < 0
        or args.turn_penalty_scale < 0
        or args.corridor_reuse_penalty < 0
        or args.max_stretch < 1
        or not 0 <= args.max_overlap <= 1
        or args.workers < 1
    ):
        raise SystemExit("Invalid candidate, stretch, or overlap setting")

    unique_rows: list[dict[str, str]] = []
    seen_ods: set[str] = set()
    with args.ods.open(newline="", encoding="utf-8-sig") as handle:
        for row in csv.DictReader(handle):
            od_id = (row.get("od_id") or "").strip()
            if not od_id:
                raise ValueError("OD row is missing od_id")
            if od_id in seen_ods:
                continue
            seen_ods.add(od_id)
            unique_rows.append(row)

    worker_count = min(args.workers, max(1, len(unique_rows)))
    print(f"Generating {len(unique_rows)} ODs with {worker_count} worker process(es)")
    tasks = [(row, args) for row in unique_rows]
    generated: list[tuple[str, Candidate]] = []

    if worker_count == 1:
        initialize_worker(str(args.graph))
        results = map(generate_row, tasks)
        for od_id, candidates, warning in results:
            if warning:
                print(warning, file=sys.stderr)
            generated.extend((od_id, candidate) for candidate in candidates)
            print(f"{od_id}: {len(candidates)} candidates")
        graph = _WORKER_GRAPH
    else:
        # On Windows, each process loads the GraphML once. Passing the graph in
        # every task would repeatedly pickle a very large object.
        with concurrent.futures.ProcessPoolExecutor(
            max_workers=worker_count,
            initializer=initialize_worker,
            initargs=(str(args.graph),),
        ) as executor:
            results = executor.map(generate_row, tasks, chunksize=1)
            for od_id, candidates, warning in results:
                if warning:
                    print(warning, file=sys.stderr)
                generated.extend((od_id, candidate) for candidate in candidates)
                print(f"{od_id}: {len(candidates)} candidates")
        # Workers are gone before this copy is loaded, limiting peak memory.
        graph, _ = build_search_graph(nx.read_graphml(args.graph))

    if graph is None:
        raise RuntimeError("Graph failed to initialize")
    write_outputs(args.routes_out, args.waypoints_out, graph, generated)
    print(f"Wrote {len(generated)} routes to {args.routes_out}")
    print(f"Wrote waypoints to {args.waypoints_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
