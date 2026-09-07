#!/usr/bin/env python3
"""Build OD route shares from map-matched observed trips.

The input must contain a realized edge sequence and should contain a realized
node sequence. Sequences may be pipe-delimited strings or JSON/Python lists.
Each distinct, ordered edge sequence is treated as one observed route.
"""

from __future__ import annotations

import argparse
import ast
import csv
import hashlib
import json
import math
import sqlite3
import sys
import tempfile
from pathlib import Path
from typing import Iterable


EDGE_COLUMNS = ("realized_edge_seq", "edge_seq", "sample_edge_seq")
NODE_COLUMNS = ("realized_node_seq", "route_node_seq", "sample_route_node_seq")
TIME_COLUMNS = (
    "realized_edge_travel_sec_seq",
    "edge_travel_sec_seq",
    "realized_edge_time_seq",
)

OUTPUT_COLUMNS = [
    "od_id",
    "route_id",
    "trip_count",
    "avg_time_sec",
    "avg_distance_m",
    "avg_speed_kph",
    "avg_circuity",
    "sample_start_node",
    "sample_end_node",
    "sample_route_node_seq",
    "sample_edge_seq",
    "share",
    "od_trip_count",
    "stable_share_norm",
    "route_rank",
    "kept_trip_total",
    "kept_route_count",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Derive OD route shares from realized observed-trip routes."
    )
    parser.add_argument("--input", required=True, type=Path, help="Observed-trip CSV")
    parser.add_argument("--output", required=True, type=Path, help="Output CSV")
    parser.add_argument(
        "--graph",
        type=Path,
        help="Optional OSMnx GraphML used for distance, speed, and circuity",
    )
    parser.add_argument(
        "--schema-sample",
        type=Path,
        help="Optional sample OD-route-share CSV whose header is checked",
    )
    parser.add_argument(
        "--min-route-trips",
        type=int,
        default=2,
        help="Drop routes observed fewer than this many times (default: 2, matching sample)",
    )
    parser.add_argument(
        "--min-route-share",
        type=float,
        default=0.0,
        help="Drop routes below this within-OD raw share (default: 0)",
    )
    parser.add_argument(
        "--chunksize",
        type=int,
        default=100_000,
        help="Commit/progress interval in input rows (default: 100000)",
    )
    parser.add_argument(
        "--delimiter", default=",", help="Input/output delimiter (default: comma)"
    )
    parser.add_argument(
        "--keep-temp-db",
        type=Path,
        help="Keep the SQLite aggregation database at this path",
    )
    return parser.parse_args()


def choose_column(fieldnames: list[str], candidates: Iterable[str]) -> str | None:
    exact = {name: name for name in fieldnames}
    folded = {name.casefold(): name for name in fieldnames}
    for candidate in candidates:
        if candidate in exact:
            return candidate
        if candidate.casefold() in folded:
            return folded[candidate.casefold()]
    return None


def parse_sequence(raw: object) -> list[str]:
    if raw is None:
        return []
    text = str(raw).strip()
    if not text or text.casefold() in {"nan", "none", "null", "[]"}:
        return []
    if text[0] in "[(":
        try:
            value = json.loads(text)
        except (json.JSONDecodeError, TypeError):
            try:
                value = ast.literal_eval(text)
            except (ValueError, SyntaxError):
                value = None
        if isinstance(value, (list, tuple)):
            return [str(item).strip() for item in value if str(item).strip()]
    # Map-matched trip files use semicolons between edges/times because each
    # edge ID itself is pipe-delimited as "from|to|key".
    separator = ";" if ";" in text else ("|" if "|" in text else ("," if "," in text else None))
    if separator:
        return [part.strip().strip("'\"") for part in text.split(separator) if part.strip()]
    return [text.strip("'\"")]


def parse_times(raw: object) -> list[float]:
    values = []
    for item in parse_sequence(raw):
        try:
            number = float(item)
        except ValueError:
            continue
        if math.isfinite(number) and number >= 0:
            values.append(number)
    return values


def edge_endpoints(edge: str) -> tuple[str, str] | None:
    # Expected OSMnx format is from_node|to_node|key. Also tolerate common
    # alternatives when pipe is already being used as the sequence delimiter.
    for separator in ("->", ",", ";", ":"):
        parts = [part.strip() for part in edge.split(separator)]
        if len(parts) >= 2 and parts[0] and parts[1]:
            return parts[0], parts[1]
    return None


def normalize_route(edges: list[str], nodes: list[str]) -> tuple[list[str], list[str]]:
    edges = [item for item in edges if item]
    nodes = [item for item in nodes if item]
    if nodes and edges and len(nodes) != len(edges) + 1:
        # Keep valid endpoints but do not claim a malformed path is complete.
        nodes = [nodes[0], nodes[-1]]
    return edges, nodes


def make_ids(origin: str, destination: str, edge_key: str) -> tuple[str, str]:
    od_id = f"{origin}__{destination}"
    digest = hashlib.sha1(edge_key.encode("utf-8")).hexdigest()[:12]
    return od_id, digest


def edge_triples(edges: list[str]) -> list[tuple[str, str, str]]:
    triples: list[tuple[str, str, str]] = []
    if edges and all(item.count("|") >= 2 for item in edges):
        for item in edges:
            parts = item.split("|")
            triples.append((parts[0], parts[1], parts[2]))
        return triples
    if len(edges) % 3 == 0:
        for index in range(0, len(edges), 3):
            triples.append((edges[index], edges[index + 1], edges[index + 2]))
    return triples


def haversine_m(a: tuple[float, float], b: tuple[float, float]) -> float:
    lon1, lat1 = map(math.radians, a)
    lon2, lat2 = map(math.radians, b)
    dlon, dlat = lon2 - lon1, lat2 - lat1
    value = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * 6_371_008.8 * math.asin(math.sqrt(value))


def load_graph_metrics(
    graph_path: Path | None,
) -> tuple[dict[tuple[str, str, str], float], dict[str, tuple[float, float]]]:
    if graph_path is None:
        return {}, {}
    if not graph_path.is_file():
        raise FileNotFoundError(graph_path)
    try:
        import networkx as nx
    except ImportError as error:
        raise ValueError("--graph requires networkx (install with: pip install networkx)") from error

    print(f"Loading graph metrics: {graph_path}")
    graph = nx.read_graphml(graph_path, node_type=str, force_multigraph=True)
    coords: dict[str, tuple[float, float]] = {}
    for node, data in graph.nodes(data=True):
        try:
            coords[str(node)] = (float(data["x"]), float(data["y"]))
        except (KeyError, TypeError, ValueError):
            pass
    lengths: dict[tuple[str, str, str], float] = {}
    for u, v, key, data in graph.edges(keys=True, data=True):
        try:
            length = float(data.get("length", "nan"))
        except (TypeError, ValueError):
            continue
        if math.isfinite(length) and length >= 0:
            lengths[(str(u), str(v), str(key))] = length
    print(f"Graph metrics: {len(lengths):,} edge lengths; {len(coords):,} node coordinates")
    return lengths, coords


def create_db(path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA synchronous=NORMAL")
    connection.execute(
        """
        CREATE TABLE routes (
            od_id TEXT NOT NULL,
            route_key TEXT NOT NULL,
            route_id TEXT NOT NULL,
            origin_node TEXT NOT NULL,
            destination_node TEXT NOT NULL,
            node_seq TEXT NOT NULL,
            edge_seq TEXT NOT NULL,
            trip_count INTEGER NOT NULL,
            timed_trip_count INTEGER NOT NULL,
            total_time_sec REAL NOT NULL,
            distance_m REAL,
            circuity REAL,
            PRIMARY KEY (od_id, route_key)
        )
        """
    )
    return connection


def aggregate(
    args: argparse.Namespace,
    connection: sqlite3.Connection,
    edge_lengths: dict[tuple[str, str, str], float],
    node_coords: dict[str, tuple[float, float]],
) -> dict[str, int]:
    stats = {"rows": 0, "accepted": 0, "empty_edges": 0, "missing_od": 0}
    with args.input.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle, delimiter=args.delimiter)
        if not reader.fieldnames:
            raise ValueError("Input CSV has no header")
        edge_col = choose_column(reader.fieldnames, EDGE_COLUMNS)
        node_col = choose_column(reader.fieldnames, NODE_COLUMNS)
        time_col = choose_column(reader.fieldnames, TIME_COLUMNS)
        if edge_col is None:
            raise ValueError(
                "No realized edge column found. Expected one of: " + ", ".join(EDGE_COLUMNS)
            )

        sql = """
            INSERT INTO routes VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
            ON CONFLICT(od_id, route_key) DO UPDATE SET
                trip_count = trip_count + 1,
                timed_trip_count = timed_trip_count + excluded.timed_trip_count,
                total_time_sec = total_time_sec + excluded.total_time_sec
        """
        batch = []
        for row in reader:
            stats["rows"] += 1
            edges = parse_sequence(row.get(edge_col))
            if not edges:
                stats["empty_edges"] += 1
                continue
            nodes = parse_sequence(row.get(node_col)) if node_col else []
            edges, nodes = normalize_route(edges, nodes)
            if len(nodes) >= 2:
                origin, destination = nodes[0], nodes[-1]
            else:
                triples = edge_triples(edges)
                if triples:
                    origin, destination = triples[0][0], triples[-1][1]
                    nodes = [triples[0][0], *[triple[1] for triple in triples]]
                else:
                    first = edge_endpoints(edges[0])
                    last = edge_endpoints(edges[-1])
                    if first is None or last is None:
                        stats["missing_od"] += 1
                        continue
                    origin, destination = first[0], last[1]
                    nodes = [origin, destination]

            triples = edge_triples(edges)
            edge_key = (
                ";".join("|".join(triple) for triple in triples)
                if triples
                else ";".join(edges)
            )
            node_key = ";".join(nodes)
            od_id, route_id = make_ids(origin, destination, edge_key)
            times = parse_times(row.get(time_col)) if time_col else []
            timed = int(bool(times))
            total_time = sum(times) if times else 0.0
            found_lengths = [edge_lengths.get(triple) for triple in triples]
            distance = (
                sum(value for value in found_lengths if value is not None)
                if triples and all(value is not None for value in found_lengths)
                else None
            )
            direct = (
                haversine_m(node_coords[origin], node_coords[destination])
                if origin in node_coords and destination in node_coords
                else None
            )
            circuity = (
                distance / direct
                if distance is not None and direct is not None and direct > 0
                else None
            )
            batch.append(
                (
                    od_id,
                    edge_key,
                    route_id,
                    origin,
                    destination,
                    node_key,
                    edge_key,
                    timed,
                    total_time,
                    distance,
                    circuity,
                )
            )
            stats["accepted"] += 1
            if len(batch) >= args.chunksize:
                connection.executemany(sql, batch)
                connection.commit()
                batch.clear()
                print(
                    f"\rRead {stats['rows']:,} rows; accepted {stats['accepted']:,}",
                    end="",
                    flush=True,
                )
        if batch:
            connection.executemany(sql, batch)
            connection.commit()
    print()
    return stats


def write_output(args: argparse.Namespace, connection: sqlite3.Connection) -> tuple[int, int]:
    args.output.parent.mkdir(parents=True, exist_ok=True)
    od_totals = dict(connection.execute("SELECT od_id, SUM(trip_count) FROM routes GROUP BY od_id"))
    query = """
        SELECT od_id, route_id, origin_node, destination_node, node_seq, edge_seq,
               trip_count, timed_trip_count, total_time_sec, distance_m, circuity
        FROM routes
        ORDER BY od_id, trip_count DESC, route_id
    """
    kept_by_od: dict[str, list[tuple]] = {}
    dropped = 0
    for record in connection.execute(query):
        od_id, _, _, _, _, _, count, _, _, _, _ = record
        raw_share = count / od_totals[od_id]
        if count < args.min_route_trips or raw_share < args.min_route_share:
            dropped += 1
            continue
        kept_by_od.setdefault(od_id, []).append(record)

    written = 0
    with args.output.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=OUTPUT_COLUMNS, delimiter=args.delimiter)
        writer.writeheader()
        for od_id in sorted(kept_by_od):
            routes = kept_by_od[od_id]
            kept_total = sum(record[6] for record in routes)
            kept_route_count = len(routes)
            od_total = od_totals[od_id]
            for rank, record in enumerate(routes, 1):
                (
                    _,
                    route_id,
                    origin,
                    destination,
                    node_seq,
                    edge_seq,
                    count,
                    timed_count,
                    total_time,
                    distance,
                    circuity,
                ) = record
                avg_time = total_time / timed_count if timed_count else None
                avg_speed = (
                    3.6 * distance / avg_time
                    if distance is not None and avg_time is not None and avg_time > 0
                    else None
                )
                writer.writerow(
                    {
                        "od_id": od_id,
                        "route_id": route_id,
                        "trip_count": count,
                        "avg_time_sec": f"{avg_time:.12g}" if avg_time is not None else "",
                        "avg_distance_m": f"{distance:.12g}" if distance is not None else "",
                        "avg_speed_kph": f"{avg_speed:.12g}" if avg_speed is not None else "",
                        "avg_circuity": f"{circuity:.12g}" if circuity is not None else "",
                        "sample_start_node": origin,
                        "sample_end_node": destination,
                        "sample_route_node_seq": node_seq,
                        "sample_edge_seq": edge_seq,
                        "share": f"{count / od_total:.12g}",
                        "od_trip_count": od_total,
                        "stable_share_norm": f"{count / kept_total:.12g}",
                        "route_rank": rank,
                        "kept_trip_total": kept_total,
                        "kept_route_count": kept_route_count,
                    }
                )
                written += 1
    return written, dropped


def validate_schema(sample_path: Path | None) -> None:
    if sample_path is None:
        print("Schema validation: skipped (no --schema-sample supplied)")
        return
    with sample_path.open("r", encoding="utf-8-sig", newline="") as handle:
        sample_header = next(csv.reader(handle), [])
    missing = [column for column in sample_header if column not in OUTPUT_COLUMNS]
    extra = [column for column in OUTPUT_COLUMNS if column not in sample_header]
    if not missing and not extra:
        print("Schema validation: exact header match")
    else:
        print("Schema validation: compatible fields were checked, but headers differ")
        print("  Sample-only columns:", ", ".join(missing) or "(none)")
        print("  Output-only columns:", ", ".join(extra) or "(none)")


def main() -> int:
    args = parse_args()
    if args.min_route_trips < 1:
        raise ValueError("--min-route-trips must be at least 1")
    if not 0.0 <= args.min_route_share <= 1.0:
        raise ValueError("--min-route-share must be between 0 and 1")
    if not args.input.is_file():
        raise FileNotFoundError(args.input)

    temp_context = None
    if args.keep_temp_db:
        db_path = args.keep_temp_db
        db_path.parent.mkdir(parents=True, exist_ok=True)
        if db_path.exists():
            raise FileExistsError(f"Refusing to overwrite existing database: {db_path}")
    else:
        temp_context = tempfile.TemporaryDirectory(prefix="od_route_shares_")
        db_path = Path(temp_context.name) / "routes.sqlite"

    edge_lengths, node_coords = load_graph_metrics(args.graph)
    connection = create_db(db_path)
    try:
        stats = aggregate(args, connection, edge_lengths, node_coords)
        written, dropped = write_output(args, connection)
    finally:
        connection.close()
        if temp_context is not None:
            temp_context.cleanup()

    validate_schema(args.schema_sample)
    print(f"Output: {args.output.resolve()}")
    print(
        f"Rows read: {stats['rows']:,}; accepted: {stats['accepted']:,}; "
        f"empty routes: {stats['empty_edges']:,}; missing OD: {stats['missing_od']:,}"
    )
    print(f"Routes written: {written:,}; routes filtered out: {dropped:,}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, sqlite3.Error) as error:
        print(f"Error: {error}", file=sys.stderr)
        raise SystemExit(2)
