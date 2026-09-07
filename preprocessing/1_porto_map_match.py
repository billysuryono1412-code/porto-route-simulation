#!/usr/bin/env python3
"""
Parallel Porto GPS map matching.

Input columns:
    TRIP_ID, TAXI_ID, TIMESTAMP, POLYLINE

POLYLINE must be a JSON list of [longitude, latitude] observations.

Outputs:
    1. A CSV matching the supplied realized-edge sample:
       - realized_edge_seq
       - realized_edge_travel_sec_seq
       - realized_node_seq
    2. A diagnostics CSV containing match quality and failure information.

Install:
    pip install "pandas>=2.0" "numpy>=1.24" "osmnx>=2.0,<3" "leuvenmapmatching>=1.1.4" "shapely>=2.0" "pyproj>=3.6" "tqdm>=4.66" "rtree>=1.2"

Example:
    python porto_map_match.py --input head.csv --output realized_edge_mapmatched.csv --workers 4

Each worker loads the cached GraphML once. Trips are streamed through a
multiprocessing pool with a live tqdm progress bar. Output rows remain in
the same order as the input rows.
"""

from __future__ import annotations

import argparse
import json
import math
import multiprocessing
import os
import sys
import time
import warnings
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Sequence

try:
    import networkx as nx
    import numpy as np
    import osmnx as ox
    import pandas as pd
    from leuvenmapmatching.map.inmem import InMemMap
    from leuvenmapmatching.matcher.distance import DistanceMatcher
    from pyproj import Transformer
    from shapely.geometry import LineString, Point
    from tqdm.auto import tqdm
except ImportError as exc:
    missing = getattr(exc, "name", str(exc))
    raise SystemExit(
        f"Missing dependency: {missing}\n"
        'Install with: pip install "pandas>=2.0" "numpy>=1.24" '
        '"osmnx>=2.0,<3" "leuvenmapmatching>=1.1.4" "shapely>=2.0" '
        '"pyproj>=3.6" "tqdm>=4.66" "rtree>=1.2"'
    ) from exc

warnings.filterwarnings("ignore", category=FutureWarning)

# Runtime configuration. CLI arguments update these values before processing.
INPUT_CSV = Path("head.csv")
OUTPUT_CSV = Path("realized_edge_mapmatched.csv")
DIAGNOSTICS_CSV = Path("map_matching_diagnostics.csv")
GRAPHML_PATH = Path("porto_drive.graphml")

SAMPLE_INTERVAL_SEC = 15.0
NETWORK_TYPE = "drive"
GRAPH_BUFFER_M = 1_000.0
FORCE_GRAPH_DOWNLOAD = False
SIMPLIFY_GRAPH = True

MAX_PLAUSIBLE_SPEED_MPS = 55.0
MAX_EXCURSION_LOOKAHEAD_POINTS = 12

MAX_MATCH_DIST_M = 120.0
MAX_INITIAL_MATCH_DIST_M = 180.0
OBSERVATION_NOISE_M = 25.0
MAX_LATTICE_WIDTH = 15
MIN_PROB_NORM = 1e-5
NON_EMITTING_LENGTH_FACTOR = 0.75

BACKTRACK_TOLERANCE_M = 25.0
BACKTRACK_PENALTY = 20.0
MIN_EDGE_LENGTH_M = 0.01

SEQUENCE_SEPARATOR = ";"
EDGE_PART_SEPARATOR = "|"
FLOAT_DECIMALS = 6
EARTH_RADIUS_M = 6_371_008.8

USE_PARALLEL = True
N_JOBS = min(4, max(1, (os.cpu_count() or 2) - 1))
PARALLEL_CHUNKSIZE = 8

# -----------------------------
# Input parsing and GPS cleaning
# -----------------------------
def haversine_m(lon1: float, lat1: float, lon2: float, lat2: float) -> float:
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = phi2 - phi1
    dlambda = math.radians(lon2 - lon1)
    a = (
        math.sin(dphi / 2.0) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2.0) ** 2
    )
    return 2.0 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(a)))


def parse_polyline(polyline_value: Any) -> list[tuple[float, float]]:
    if pd.isna(polyline_value):
        return []
    raw = json.loads(polyline_value) if isinstance(polyline_value, str) else polyline_value
    points: list[tuple[float, float]] = []
    for item in raw:
        if not isinstance(item, (list, tuple)) or len(item) < 2:
            continue
        lon, lat = float(item[0]), float(item[1])
        if not (math.isfinite(lon) and math.isfinite(lat)):
            continue
        if -180 <= lon <= 180 and -90 <= lat <= 90:
            points.append((lon, lat))
    return points


def build_observation_frame(row: pd.Series) -> pd.DataFrame:
    points = parse_polyline(row["POLYLINE"])
    if not points:
        return pd.DataFrame(columns=["original_index", "lon", "lat", "time_sec"])
    start_time = float(row["TIMESTAMP"])
    return pd.DataFrame(
        {
            "original_index": np.arange(len(points), dtype=int),
            "lon": [p[0] for p in points],
            "lat": [p[1] for p in points],
            "time_sec": [start_time + i * SAMPLE_INTERVAL_SEC for i in range(len(points))],
        }
    )


def clean_short_gps_excursions(
    observations: pd.DataFrame,
    max_speed_mps: float = MAX_PLAUSIBLE_SPEED_MPS,
    max_lookahead: int = MAX_EXCURSION_LOOKAHEAD_POINTS,
) -> tuple[pd.DataFrame, list[int]]:
    """Remove short impossible-speed excursions without compressing timestamps."""
    if len(observations) <= 2:
        return observations.copy().reset_index(drop=True), []

    obs = observations.reset_index(drop=True)
    keep_positions = [0]
    removed_original_indices: list[int] = []
    i = 0

    while i < len(obs) - 1:
        current = obs.iloc[i]
        nxt = obs.iloc[i + 1]
        dt = float(nxt["time_sec"] - current["time_sec"])
        distance = haversine_m(
            float(current["lon"]), float(current["lat"]),
            float(nxt["lon"]), float(nxt["lat"]),
        )
        speed = distance / max(dt, 1e-9)

        if speed <= max_speed_mps:
            i += 1
            keep_positions.append(i)
            continue

        recovery_position = None
        upper = min(len(obs), i + 1 + max_lookahead)
        for j in range(i + 2, upper):
            candidate = obs.iloc[j]
            bridge_dt = float(candidate["time_sec"] - current["time_sec"])
            bridge_distance = haversine_m(
                float(current["lon"]), float(current["lat"]),
                float(candidate["lon"]), float(candidate["lat"]),
            )
            if bridge_distance / max(bridge_dt, 1e-9) <= max_speed_mps:
                recovery_position = j
                break

        if recovery_position is None:
            i += 1
            keep_positions.append(i)
        else:
            for pos in range(i + 1, recovery_position):
                removed_original_indices.append(int(obs.iloc[pos]["original_index"]))
            i = recovery_position
            keep_positions.append(i)

    cleaned = obs.iloc[sorted(set(keep_positions))].copy().reset_index(drop=True)
    return cleaned, removed_original_indices


def all_clean_observations(trip_table: pd.DataFrame) -> pd.DataFrame:
    frames = []
    for _, row in trip_table.iterrows():
        cleaned, _ = clean_short_gps_excursions(build_observation_frame(row))
        if not cleaned.empty:
            frames.append(cleaned[["lon", "lat"]])
    if not frames:
        raise ValueError("No valid GPS coordinates were found.")
    return pd.concat(frames, ignore_index=True)


# -----------------------------
# OSM graph and Leuven matcher
# -----------------------------
def buffered_bbox(points: pd.DataFrame, buffer_m: float) -> tuple[float, float, float, float]:
    """OSMnx 2.x bbox order: (left, bottom, right, top)."""
    center_lat = float(points["lat"].mean())
    lat_buffer = buffer_m / 111_320.0
    lon_buffer = buffer_m / max(1.0, 111_320.0 * math.cos(math.radians(center_lat)))
    return (
        float(points["lon"].min()) - lon_buffer,
        float(points["lat"].min()) - lat_buffer,
        float(points["lon"].max()) + lon_buffer,
        float(points["lat"].max()) + lat_buffer,
    )


def load_or_download_graph(all_points: pd.DataFrame) -> nx.MultiDiGraph:
    if GRAPHML_PATH.exists() and not FORCE_GRAPH_DOWNLOAD:
        print(f"Loading cached graph: {GRAPHML_PATH.resolve()}")
        graph = ox.io.load_graphml(GRAPHML_PATH)
    else:
        bbox = buffered_bbox(all_points, GRAPH_BUFFER_M)
        print("Downloading graph for bbox (left, bottom, right, top):", bbox)
        graph = ox.graph.graph_from_bbox(
            bbox,
            network_type=NETWORK_TYPE,
            simplify=SIMPLIFY_GRAPH,
            retain_all=False,
            truncate_by_edge=True,
        )
        ox.io.save_graphml(graph, filepath=GRAPHML_PATH)
        print(f"Saved graph: {GRAPHML_PATH.resolve()}")
    if not isinstance(graph, nx.MultiDiGraph):
        graph = nx.MultiDiGraph(graph)
    if graph.number_of_nodes() == 0 or graph.number_of_edges() == 0:
        raise ValueError("Road graph is empty.")
    return graph


def build_leuven_map(graph: nx.MultiDiGraph) -> InMemMap:
    map_con = InMemMap("porto_drive", use_latlon=True, use_rtree=True, index_edges=True)
    for node_id, data in graph.nodes(data=True):
        map_con.add_node(int(node_id), (float(data["y"]), float(data["x"])))
    seen: set[tuple[int, int]] = set()
    for u, v in graph.edges():
        pair = (int(u), int(v))
        if pair not in seen:
            map_con.add_edge(*pair)
            seen.add(pair)
    return map_con


def collapse_consecutive(values: Sequence[Any]) -> list[Any]:
    out: list[Any] = []
    for value in values:
        if not out or value != out[-1]:
            out.append(value)
    return out


def match_observations_to_nodes(observations: pd.DataFrame, map_con: InMemMap) -> list[int]:
    if len(observations) < 2:
        raise ValueError("At least two GPS observations are required.")
    matcher = DistanceMatcher(
        map_con,
        max_dist=MAX_MATCH_DIST_M,
        max_dist_init=MAX_INITIAL_MATCH_DIST_M,
        obs_noise=OBSERVATION_NOISE_M,
        min_prob_norm=MIN_PROB_NORM,
        non_emitting_states=True,
        non_emitting_length_factor=NON_EMITTING_LENGTH_FACTOR,
        max_lattice_width=MAX_LATTICE_WIDTH,
        only_edges=True,
    )
    gps_path = [(float(lat), float(lon)) for lon, lat in observations[["lon", "lat"]].itertuples(index=False, name=None)]
    _, last_idx = matcher.match(gps_path)
    if last_idx < len(gps_path) - 1:
        raise RuntimeError(f"Only matched through observation {last_idx}/{len(gps_path)-1}")
    nodes = collapse_consecutive([int(n) for n in matcher.path_pred_onlynodes])
    if len(nodes) < 2:
        raise RuntimeError("Map matcher returned fewer than two nodes.")
    return nodes


def repair_disconnected_node_pairs(graph: nx.MultiDiGraph, nodes: Sequence[int]) -> list[int]:
    repaired = [int(nodes[0])]
    for target_raw in nodes[1:]:
        source, target = repaired[-1], int(target_raw)
        if graph.has_edge(source, target):
            repaired.append(target)
            continue
        try:
            bridge = nx.shortest_path(graph, source, target, weight="length")
        except (nx.NetworkXNoPath, nx.NodeNotFound) as exc:
            raise RuntimeError(f"Cannot connect matched nodes {source}->{target}") from exc
        repaired.extend(int(n) for n in bridge[1:])
    return collapse_consecutive(repaired)


# -----------------------------
# Edge geometry and time allocation
# -----------------------------
@dataclass
class MatchedEdge:
    u: int
    v: int
    key: Any
    edge_id: str
    geometry_m: LineString
    length_m: float
    cumulative_start_m: float
    cumulative_end_m: float


def choose_osmnx_edge_key(graph: nx.MultiDiGraph, u: int, v: int) -> Any:
    edge_dict = graph.get_edge_data(u, v)
    if not edge_dict:
        raise KeyError(f"No directed edge for {u}->{v}")
    return min(
        edge_dict,
        key=lambda key: float(edge_dict[key].get("length", float("inf"))),
    )


def oriented_edge_geometry(projected_graph: nx.MultiDiGraph, u: int, v: int, key: Any) -> LineString:
    data = projected_graph.get_edge_data(u, v, key)
    if data is None:
        raise KeyError(f"Projected edge missing: {(u, v, key)}")
    u_point = Point(float(projected_graph.nodes[u]["x"]), float(projected_graph.nodes[u]["y"]))
    v_point = Point(float(projected_graph.nodes[v]["x"]), float(projected_graph.nodes[v]["y"]))
    geom = data.get("geometry") or LineString([u_point, v_point])
    if not isinstance(geom, LineString):
        geom = LineString(geom)
    coords = list(geom.coords)
    forward = Point(coords[0]).distance(u_point) + Point(coords[-1]).distance(v_point)
    reverse = Point(coords[-1]).distance(u_point) + Point(coords[0]).distance(v_point)
    return LineString(coords[::-1]) if reverse < forward else geom


def build_matched_edges(graph: nx.MultiDiGraph, projected_graph: nx.MultiDiGraph, nodes: Sequence[int]) -> list[MatchedEdge]:
    edges: list[MatchedEdge] = []
    cumulative = 0.0
    for u, v in zip(nodes[:-1], nodes[1:]):
        key = choose_osmnx_edge_key(graph, int(u), int(v))
        geom = oriented_edge_geometry(projected_graph, int(u), int(v), key)
        length = max(MIN_EDGE_LENGTH_M, float(geom.length))
        edge_id = f"{int(u)}{EDGE_PART_SEPARATOR}{int(v)}{EDGE_PART_SEPARATOR}{key}"
        edges.append(MatchedEdge(int(u), int(v), key, edge_id, geom, length, cumulative, cumulative + length))
        cumulative += length
    if not edges:
        raise RuntimeError("No matched edges were created.")
    return edges


def project_observations(observations: pd.DataFrame, transformer: Transformer) -> list[Point]:
    xs, ys = transformer.transform(observations["lon"].to_numpy(float), observations["lat"].to_numpy(float))
    return [Point(float(x), float(y)) for x, y in zip(xs, ys)]


def monotonic_route_positions(points_m: Sequence[Point], edges: Sequence[MatchedEdge]) -> tuple[np.ndarray, np.ndarray]:
    positions, snaps = [], []
    previous = 0.0
    for point in points_m:
        best_score, best_position, best_snap = float("inf"), previous, float("inf")
        for edge in edges:
            local = float(edge.geometry_m.project(point))
            candidate = edge.cumulative_start_m + local
            snap = float(point.distance(edge.geometry_m))
            backtrack = max(0.0, previous - candidate - BACKTRACK_TOLERANCE_M)
            score = snap + BACKTRACK_PENALTY * backtrack
            if score < best_score:
                best_score, best_position, best_snap = score, candidate, snap
        best_position = min(edges[-1].cumulative_end_m, max(previous, best_position))
        positions.append(best_position)
        snaps.append(best_snap)
        previous = best_position
    return np.asarray(positions), np.asarray(snaps)


def edge_index_at_position(edges: Sequence[MatchedEdge], position_m: float) -> int:
    ends = np.asarray([e.cumulative_end_m for e in edges])
    return min(max(int(np.searchsorted(ends, position_m, side="right")), 0), len(edges) - 1)


def allocate_times_to_edges(observations: pd.DataFrame, positions: np.ndarray, edges: Sequence[MatchedEdge]) -> np.ndarray:
    edge_times = np.zeros(len(edges), dtype=float)
    times = observations["time_sec"].to_numpy(float)
    for i in range(len(times) - 1):
        dt = float(times[i + 1] - times[i])
        if not math.isfinite(dt) or dt <= 0:
            continue
        start, end = float(positions[i]), float(positions[i + 1])
        if end - start <= 1e-6:
            edge_times[edge_index_at_position(edges, start)] += dt
            continue
        overlaps = np.asarray([
            max(0.0, min(end, e.cumulative_end_m) - max(start, e.cumulative_start_m))
            for e in edges
        ])
        if overlaps.sum() <= 1e-9:
            edge_times[edge_index_at_position(edges, (start + end) / 2)] += dt
        else:
            edge_times += dt * overlaps / overlaps.sum()
    return edge_times


def trim_result(edges: Sequence[MatchedEdge], edge_times: np.ndarray, positions: np.ndarray) -> tuple[list[MatchedEdge], np.ndarray]:
    first = edge_index_at_position(edges, float(positions[0]))
    last = max(first, edge_index_at_position(edges, float(positions[-1])))
    return list(edges[first:last + 1]), edge_times[first:last + 1]


def format_float_sequence(values: Iterable[float]) -> str:
    return SEQUENCE_SEPARATOR.join(f"{float(v):.{FLOAT_DECIMALS}f}" for v in values)


def process_trip(row: pd.Series, graph: nx.MultiDiGraph, projected_graph: nx.MultiDiGraph, map_con: InMemMap, transformer: Transformer):
    raw = build_observation_frame(row)
    if len(raw) < 2:
        raise ValueError("Polyline contains fewer than two points.")
    clean, removed = clean_short_gps_excursions(raw)
    if len(clean) < 2:
        raise ValueError("Fewer than two points remain after cleaning.")

    nodes = repair_disconnected_node_pairs(graph, match_observations_to_nodes(clean, map_con))
    edges = build_matched_edges(graph, projected_graph, nodes)
    points_m = project_observations(clean, transformer)
    positions, snap_distances = monotonic_route_positions(points_m, edges)
    edge_times = allocate_times_to_edges(clean, positions, edges)
    edges, edge_times = trim_result(edges, edge_times, positions)

    node_sequence = [edges[0].u] + [edge.v for edge in edges]
    duration = float(clean["time_sec"].iloc[-1] - clean["time_sec"].iloc[0])
    allocated = float(edge_times.sum())

    output = {
        "realized_edge_seq": SEQUENCE_SEPARATOR.join(e.edge_id for e in edges),
        "realized_edge_travel_sec_seq": format_float_sequence(edge_times),
        "realized_node_seq": SEQUENCE_SEPARATOR.join(map(str, node_sequence)),
    }
    diagnostic = {
        "TRIP_ID": row["TRIP_ID"],
        "TAXI_ID": row["TAXI_ID"],
        "status": "ok",
        "raw_point_count": len(raw),
        "clean_point_count": len(clean),
        "removed_point_count": len(removed),
        "removed_original_indices": SEQUENCE_SEPARATOR.join(map(str, removed)),
        "matched_node_count": len(node_sequence),
        "matched_edge_count": len(edges),
        "observed_duration_sec": duration,
        "allocated_duration_sec": allocated,
        "duration_error_sec": allocated - duration,
        "route_progress_m": float(positions[-1] - positions[0]),
        "mean_snap_distance_m": float(np.mean(snap_distances)),
        "max_snap_distance_m": float(np.max(snap_distances)),
        "error": "",
    }
    debug = {"raw": raw, "clean": clean, "edges": edges, "edge_times": edge_times}
    return output, diagnostic, debug



def failed_trip_result(row: Any, exc: Exception) -> tuple[dict[str, str], dict[str, Any]]:
    """Return schema-compatible empty output and a diagnostic failure row."""
    try:
        raw_count = len(parse_polyline(row.get("POLYLINE", "")))
    except Exception:
        raw_count = np.nan

    output = {
        "realized_edge_seq": "",
        "realized_edge_travel_sec_seq": "",
        "realized_node_seq": "",
    }
    diagnostic = {
        "TRIP_ID": row.get("TRIP_ID", ""),
        "TAXI_ID": row.get("TAXI_ID", ""),
        "status": "failed",
        "raw_point_count": raw_count,
        "clean_point_count": np.nan,
        "removed_point_count": np.nan,
        "removed_original_indices": "",
        "matched_node_count": 0,
        "matched_edge_count": 0,
        "observed_duration_sec": np.nan,
        "allocated_duration_sec": np.nan,
        "duration_error_sec": np.nan,
        "route_progress_m": np.nan,
        "mean_snap_distance_m": np.nan,
        "max_snap_distance_m": np.nan,
        "error": f"{type(exc).__name__}: {exc}",
    }
    return output, diagnostic


def process_trip_safely(
    row: Any,
    graph: nx.MultiDiGraph,
    projected_graph: nx.MultiDiGraph,
    map_con: InMemMap,
    transformer: Transformer,
) -> tuple[dict[str, Any], dict[str, Any]]:
    try:
        output, diagnostic, _ = process_trip(
            row, graph, projected_graph, map_con, transformer
        )
        return output, diagnostic
    except Exception as exc:
        return failed_trip_result(row, exc)


def load_worker_graph(graphml_path: str) -> tuple[
    nx.MultiDiGraph, nx.MultiDiGraph, InMemMap, Transformer
]:
    """Load and prepare an independent graph copy inside one worker process."""
    graph = ox.io.load_graphml(graphml_path)
    if not isinstance(graph, nx.MultiDiGraph):
        graph = nx.MultiDiGraph(graph)
    projected_graph = ox.projection.project_graph(graph)
    map_con = build_leuven_map(graph)
    transformer = Transformer.from_crs(
        graph.graph.get("crs", "EPSG:4326"),
        projected_graph.graph["crs"],
        always_xy=True,
    )
    return graph, projected_graph, map_con, transformer



# Worker-local graph objects, initialized once per child process.
_WORKER_GRAPH: nx.MultiDiGraph | None = None
_WORKER_PROJECTED_GRAPH: nx.MultiDiGraph | None = None
_WORKER_MAP: InMemMap | None = None
_WORKER_TRANSFORMER: Transformer | None = None


def initialize_map_matching_worker(graphml_path: str) -> None:
    global _WORKER_GRAPH, _WORKER_PROJECTED_GRAPH
    global _WORKER_MAP, _WORKER_TRANSFORMER

    (
        _WORKER_GRAPH,
        _WORKER_PROJECTED_GRAPH,
        _WORKER_MAP,
        _WORKER_TRANSFORMER,
    ) = load_worker_graph(graphml_path)


def process_indexed_trip(
    task: tuple[int, dict[str, Any]],
) -> tuple[int, dict[str, Any], dict[str, Any]]:
    row_index, row = task

    if (
        _WORKER_GRAPH is None
        or _WORKER_PROJECTED_GRAPH is None
        or _WORKER_MAP is None
        or _WORKER_TRANSFORMER is None
    ):
        raise RuntimeError("Map-matching worker was not initialized.")

    output, diagnostic = process_trip_safely(
        row,
        _WORKER_GRAPH,
        _WORKER_PROJECTED_GRAPH,
        _WORKER_MAP,
        _WORKER_TRANSFORMER,
    )
    return row_index, output, diagnostic


def iter_indexed_trip_records(
    trip_table: pd.DataFrame,
) -> Iterable[tuple[int, dict[str, Any]]]:
    """Yield rows lazily instead of creating 1.7 million dictionaries at once."""
    columns = list(trip_table.columns)
    for row_index, values in enumerate(
        trip_table.itertuples(index=False, name=None)
    ):
        yield row_index, dict(zip(columns, values))


def process_all_trips(
    trip_table: pd.DataFrame,
    graph: nx.MultiDiGraph | None,
    projected_graph: nx.MultiDiGraph | None,
    map_con: InMemMap | None,
    transformer: Transformer | None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Map-match trips with a live completed-trip progress bar."""
    worker_count = max(1, min(int(N_JOBS), len(trip_table)))

    if not USE_PARALLEL or worker_count == 1:
        if (
            graph is None
            or projected_graph is None
            or map_con is None
            or transformer is None
        ):
            raise RuntimeError("Sequential graph objects were not initialized.")

        results = []
        for row_index, row in tqdm(
            trip_table.iterrows(),
            total=len(trip_table),
            desc="Map matching",
            unit="trip",
            dynamic_ncols=True,
            smoothing=0.05,
        ):
            output, diagnostic = process_trip_safely(
                row, graph, projected_graph, map_con, transformer
            )
            results.append((int(row_index), output, diagnostic))
    else:
        graphml_path = str(GRAPHML_PATH.resolve())

        print(f"Starting {worker_count} map-matching workers.")
        print(
            "Each worker loads the graph once. The progress bar begins after "
            "the first worker finishes graph/index initialization."
        )

        context = multiprocessing.get_context(
            "spawn" if os.name == "nt" else "fork"
        )
        results = []

        with context.Pool(
            processes=worker_count,
            initializer=initialize_map_matching_worker,
            initargs=(graphml_path,),
        ) as pool:
            iterator = pool.imap_unordered(
                process_indexed_trip,
                iter_indexed_trip_records(trip_table),
                chunksize=max(1, int(PARALLEL_CHUNKSIZE)),
            )

            for result in tqdm(
                iterator,
                total=len(trip_table),
                desc="Map matching",
                unit="trip",
                dynamic_ncols=True,
                smoothing=0.05,
                mininterval=0.5,
            ):
                results.append(result)

    results.sort(key=lambda item: item[0])
    outputs = [item[1] for item in results]
    diagnostics = [item[2] for item in results]
    return outputs, diagnostics


OUTPUT_COLUMNS = [
    "realized_edge_seq",
    "realized_edge_travel_sec_seq",
    "realized_node_seq",
]

REQUIRED_INPUT_COLUMNS = {"TRIP_ID", "TAXI_ID", "TIMESTAMP", "POLYLINE"}


def count_sequence_parts(value: Any) -> int:
    if pd.isna(value) or str(value).strip() == "":
        return 0
    return len(str(value).split(SEQUENCE_SEPARATOR))


def validate_results(
    result_df: pd.DataFrame,
    diagnostics_df: pd.DataFrame,
) -> None:
    edge_counts = result_df["realized_edge_seq"].map(count_sequence_parts)
    time_counts = result_df["realized_edge_travel_sec_seq"].map(count_sequence_parts)
    node_counts = result_df["realized_node_seq"].map(count_sequence_parts)

    if not (edge_counts == time_counts).all():
        bad = int((edge_counts != time_counts).sum())
        raise RuntimeError(f"{bad} rows have different edge/time sequence lengths.")

    valid_node_shape = (
        ((edge_counts == 0) & (node_counts == 0))
        | (node_counts == edge_counts + 1)
    )
    if not valid_node_shape.all():
        bad = int((~valid_node_shape).sum())
        raise RuntimeError(f"{bad} rows do not satisfy nodes = edges + 1.")

    ok = diagnostics_df["status"].eq("ok")
    if ok.any():
        max_error = float(
            diagnostics_df.loc[ok, "duration_error_sec"].abs().max()
        )
        if not math.isfinite(max_error) or max_error >= 1e-5:
            raise RuntimeError(
                f"Allocated edge times do not preserve duration; "
                f"maximum error={max_error:.9f} seconds."
            )


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Map-match Porto taxi GPS polylines to directed OSM edges and "
            "allocate observed travel time across those edges."
        )
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=INPUT_CSV,
        help="Input CSV containing TRIP_ID, TAXI_ID, TIMESTAMP, and POLYLINE.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=OUTPUT_CSV,
        help="Output CSV containing edge, edge-time, and node sequences.",
    )
    parser.add_argument(
        "--diagnostics",
        type=Path,
        default=DIAGNOSTICS_CSV,
        help="Diagnostics CSV path.",
    )
    parser.add_argument(
        "--graph",
        type=Path,
        default=GRAPHML_PATH,
        help="GraphML cache path.",
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=N_JOBS,
        help=(
            "Worker processes. Each worker holds its own graph. "
            "Use 1 for sequential execution."
        ),
    )
    parser.add_argument(
        "--sample-interval-sec",
        type=float,
        default=SAMPLE_INTERVAL_SEC,
        help="Seconds between consecutive POLYLINE observations.",
    )
    parser.add_argument(
        "--graph-buffer-m",
        type=float,
        default=GRAPH_BUFFER_M,
        help="Extra distance around all input GPS points when downloading OSM.",
    )
    parser.add_argument(
        "--network-type",
        default=NETWORK_TYPE,
        help="OSMnx network type, normally 'drive'.",
    )
    parser.add_argument(
        "--force-download",
        action="store_true",
        help="Ignore the GraphML cache and download a new graph.",
    )
    parser.add_argument(
        "--max-speed-mps",
        type=float,
        default=MAX_PLAUSIBLE_SPEED_MPS,
        help="Threshold used to remove short impossible-speed GPS excursions.",
    )
    parser.add_argument(
        "--max-match-distance-m",
        type=float,
        default=MAX_MATCH_DIST_M,
        help="Maximum Leuven matching distance.",
    )
    parser.add_argument(
        "--initial-match-distance-m",
        type=float,
        default=MAX_INITIAL_MATCH_DIST_M,
        help="Maximum matching distance for the first observation.",
    )
    parser.add_argument(
        "--observation-noise-m",
        type=float,
        default=OBSERVATION_NOISE_M,
        help="Expected GPS observation noise used by the matcher.",
    )
    parser.add_argument(
        "--chunksize",
        type=int,
        default=PARALLEL_CHUNKSIZE,
        help=(
            "Trips dispatched to a worker at a time. Smaller values make "
            "progress smoother; larger values reduce scheduling overhead."
        ),
    )
    parser.add_argument(
        "--merge-input-columns",
        action="store_true",
        help="Prepend the original input columns to the output CSV.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="Process only the first N rows; 0 processes every row.",
    )
    return parser


def apply_arguments(args: argparse.Namespace) -> None:
    global INPUT_CSV, OUTPUT_CSV, DIAGNOSTICS_CSV, GRAPHML_PATH
    global N_JOBS, USE_PARALLEL, SAMPLE_INTERVAL_SEC
    global GRAPH_BUFFER_M, NETWORK_TYPE, FORCE_GRAPH_DOWNLOAD
    global MAX_PLAUSIBLE_SPEED_MPS, MAX_MATCH_DIST_M
    global MAX_INITIAL_MATCH_DIST_M, OBSERVATION_NOISE_M, PARALLEL_CHUNKSIZE

    INPUT_CSV = args.input
    OUTPUT_CSV = args.output
    DIAGNOSTICS_CSV = args.diagnostics
    GRAPHML_PATH = args.graph

    N_JOBS = max(1, int(args.workers))
    USE_PARALLEL = N_JOBS > 1
    SAMPLE_INTERVAL_SEC = float(args.sample_interval_sec)
    GRAPH_BUFFER_M = float(args.graph_buffer_m)
    NETWORK_TYPE = str(args.network_type)
    FORCE_GRAPH_DOWNLOAD = bool(args.force_download)
    MAX_PLAUSIBLE_SPEED_MPS = float(args.max_speed_mps)
    MAX_MATCH_DIST_M = float(args.max_match_distance_m)
    MAX_INITIAL_MATCH_DIST_M = float(args.initial_match_distance_m)
    OBSERVATION_NOISE_M = float(args.observation_noise_m)
    PARALLEL_CHUNKSIZE = max(1, int(args.chunksize))

    if SAMPLE_INTERVAL_SEC <= 0:
        raise ValueError("--sample-interval-sec must be positive.")
    if GRAPH_BUFFER_M < 0:
        raise ValueError("--graph-buffer-m cannot be negative.")
    if MAX_PLAUSIBLE_SPEED_MPS <= 0:
        raise ValueError("--max-speed-mps must be positive.")
    if MAX_MATCH_DIST_M <= 0 or MAX_INITIAL_MATCH_DIST_M <= 0:
        raise ValueError("Matching distances must be positive.")
    if OBSERVATION_NOISE_M <= 0:
        raise ValueError("--observation-noise-m must be positive.")


def ensure_parent_directory(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_argument_parser()
    args = parser.parse_args(argv)
    apply_arguments(args)

    if not INPUT_CSV.exists():
        parser.error(f"Input CSV does not exist: {INPUT_CSV}")

    ensure_parent_directory(OUTPUT_CSV)
    ensure_parent_directory(DIAGNOSTICS_CSV)
    ensure_parent_directory(GRAPHML_PATH)

    trips = pd.read_csv(INPUT_CSV).reset_index(drop=True)
    if args.limit > 0:
        trips = trips.head(args.limit).copy()

    missing = REQUIRED_INPUT_COLUMNS - set(trips.columns)
    if missing:
        raise ValueError(f"Missing required columns: {sorted(missing)}")
    if trips.empty:
        raise ValueError("The input CSV contains no trips.")

    print(f"Input: {INPUT_CSV.resolve()}")
    print(f"Trips: {len(trips):,}")
    print(
        f"Execution: {'parallel' if USE_PARALLEL else 'sequential'} "
        f"with {min(N_JOBS, len(trips))} worker(s)"
    )

    started = time.perf_counter()

    graph: nx.MultiDiGraph | None = None
    projected_graph: nx.MultiDiGraph | None = None
    map_con: InMemMap | None = None
    transformer: Transformer | None = None

    if GRAPHML_PATH.exists() and not FORCE_GRAPH_DOWNLOAD:
        print(f"Using existing GraphML: {GRAPHML_PATH.resolve()}")
    else:
        print("GraphML not found or forced download requested.")
        print("Scanning GPS observations to determine the download boundary...")
        all_points = all_clean_observations(trips)
        downloaded_graph = load_or_download_graph(all_points)
        print(
            f"Downloaded graph: {downloaded_graph.number_of_nodes():,} nodes, "
            f"{downloaded_graph.number_of_edges():,} directed edges"
        )
        del downloaded_graph

    if not USE_PARALLEL or N_JOBS == 1:
        print("Loading graph for sequential matching...")
        graph = ox.io.load_graphml(GRAPHML_PATH)
        if not isinstance(graph, nx.MultiDiGraph):
            graph = nx.MultiDiGraph(graph)

        print(
            f"Graph: {graph.number_of_nodes():,} nodes, "
            f"{graph.number_of_edges():,} directed edges"
        )
        print("Projecting graph...")
        projected_graph = ox.projection.project_graph(graph)
        print("Building Leuven spatial index...")
        map_con = build_leuven_map(graph)
        transformer = Transformer.from_crs(
            graph.graph.get("crs", "EPSG:4326"),
            projected_graph.graph["crs"],
            always_xy=True,
        )
    else:
        print(
            "Parallel mode: the main process skips its own projected graph "
            "and Leuven index."
        )

    outputs, diagnostics = process_all_trips(
        trips,
        graph,
        projected_graph,
        map_con,
        transformer,
    )

    result_df = pd.DataFrame(outputs, columns=OUTPUT_COLUMNS)
    diagnostics_df = pd.DataFrame(diagnostics)

    validate_results(result_df, diagnostics_df)

    if args.merge_input_columns:
        output_df = pd.concat(
            [trips.reset_index(drop=True), result_df.reset_index(drop=True)],
            axis=1,
        )
    else:
        output_df = result_df

    output_df.to_csv(OUTPUT_CSV, index=False)
    diagnostics_df.to_csv(DIAGNOSTICS_CSV, index=False)

    elapsed = time.perf_counter() - started
    success_count = int(diagnostics_df["status"].eq("ok").sum())
    failure_count = int(diagnostics_df["status"].eq("failed").sum())

    print(f"Saved output: {OUTPUT_CSV.resolve()}")
    print(f"Saved diagnostics: {DIAGNOSTICS_CSV.resolve()}")
    print(
        f"Completed in {elapsed:.2f} seconds | "
        f"successful={success_count:,} | failed={failure_count:,}"
    )

    if failure_count:
        print(
            "Some trips failed. Inspect the diagnostics CSV for their errors.",
            file=sys.stderr,
        )

    return 0


if __name__ == "__main__":
    multiprocessing.freeze_support()
    raise SystemExit(main())
