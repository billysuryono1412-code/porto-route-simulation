#!/usr/bin/env python3
"""
Infer hourly background traffic from realized edge travel times by inverting
the congestion equation used by the Java simulation.

Java congestion model:
    t = t0 * (1 + alpha * (flow / capacity)^beta)

Default Java parameters:
    alpha = 0.5
    beta = 2.0
    capacity = clamp(edge_traversal_count / 20, 3, 20)

Inverse:
    flow = capacity * ((t / t0 - 1) / alpha) ** (1 / beta)

The observed vehicle itself contributes one unit to total flow. By default,
this script subtracts one vehicle so the output can be used as backgroundFlow
while the Java simulator contributes its own liveFlow.

The script writes:
  1. Hourly background-flow CSV for backgroundTrafficCsv.
  2. A companion all-day FREE-FLOW edge lookup for edgeLookup.

Using the old observed hourly mean edge times as edgeLookup together with the
new inferred background flow would double-count congestion. Point edgeLookup
to the generated free-flow file.

Expected observed-trip columns:
    TIMESTAMP
    realized_edge_seq
    realized_edge_travel_sec_seq

Example:
    python infer_background_from_edge_times.py --input porto_train_edge_mapmatched.csv --graph data/porto.graphml --output background_congestion_flow.csv --free-flow-output sim_edge_lookup_freeflow.csv --workers 8 --warmup-days 5
"""

from __future__ import annotations

import argparse
import ast as literal_ast
import math
import multiprocessing
import os
import re
import sys
import time
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable, Iterator

try:
    import networkx as nx
    import pandas as pd
    from tqdm.auto import tqdm
except ImportError as exc:
    raise SystemExit(
        f"Missing dependency: {getattr(exc, 'name', exc)}\n"
        'Install with: pip install "networkx>=3.2" "pandas>=2.0" "tqdm>=4.66"'
    ) from exc


EDGE_SEPARATOR = ";"
DEFAULT_TIMESTAMP_COLUMN = "TIMESTAMP"
DEFAULT_EDGE_COLUMN = "realized_edge_seq"
DEFAULT_TIME_COLUMN = "realized_edge_travel_sec_seq"

# Worker-local immutable configuration.
_WORKER_FREE_FLOW: dict[str, float] = {}
_WORKER_ALPHA = 0.5
_WORKER_MAX_TIME_RATIO = 10.0
_WORKER_WINDOW_START = float("-inf")
_WORKER_WINDOW_END = float("inf")
_WORKER_TIMESTAMP_COLUMN = DEFAULT_TIMESTAMP_COLUMN
_WORKER_EDGE_COLUMN = DEFAULT_EDGE_COLUMN
_WORKER_TIME_COLUMN = DEFAULT_TIME_COLUMN


@dataclass
class GraphEdgeInfo:
    edge_id: str
    length_m: float
    speed_kph: float
    free_flow_sec: float
    source: str


@dataclass
class ChunkStats:
    rows: int = 0
    valid_rows: int = 0
    empty_rows: int = 0
    mismatched_rows: int = 0
    malformed_rows: int = 0
    invalid_time_rows: int = 0
    traversals: int = 0
    retained_traversals: int = 0
    missing_graph_edges: int = 0
    below_free_flow: int = 0
    clipped_high: int = 0

    def add(self, other: "ChunkStats") -> None:
        for field in self.__dataclass_fields__:
            setattr(self, field, getattr(self, field) + getattr(other, field))


@dataclass(frozen=True)
class StudyWindow:
    first_date: date
    last_date: date
    retained_first_date: date
    warmup_days: int
    days_averaged: int
    start_epoch: float
    end_epoch: float


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Infer hourly background traffic by inverting the Java BPR-like "
            "edge congestion equation."
        )
    )
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--graph", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--free-flow-output", type=Path, required=True)

    parser.add_argument("--timestamp-column", default=DEFAULT_TIMESTAMP_COLUMN)
    parser.add_argument("--edge-column", default=DEFAULT_EDGE_COLUMN)
    parser.add_argument("--time-column", default=DEFAULT_TIME_COLUMN)

    parser.add_argument("--workers", type=int, default=min(8, max(1, (os.cpu_count() or 2) - 1)))
    parser.add_argument("--chunk-rows", type=int, default=5_000)
    parser.add_argument("--warmup-days", type=int, default=5)
    parser.add_argument("--limit", type=int, default=0)

    parser.add_argument("--alpha", type=float, default=0.5)
    parser.add_argument("--beta", type=float, default=2.0)
    parser.add_argument("--capacity-divisor", type=float, default=20.0)
    parser.add_argument("--min-capacity", type=float, default=3.0)
    parser.add_argument("--max-capacity", type=float, default=20.0)
    parser.add_argument(
        "--self-vehicle-flow",
        type=float,
        default=1.0,
        help=(
            "Subtract this from inferred total flow to obtain background flow. "
            "Use 0 to keep total inferred flow."
        ),
    )
    parser.add_argument(
        "--max-time-ratio",
        type=float,
        default=10.0,
        help=(
            "Cap each observed/free-flow time ratio before inversion to prevent "
            "single map-matching outliers from dominating."
        ),
    )
    parser.add_argument(
        "--min-observations",
        type=int,
        default=1,
        help="Minimum traversals required for an edge-hour output row.",
    )
    parser.add_argument(
        "--missing-free-flow-sec",
        type=float,
        default=30.0,
        help="Fallback matching the Java edgeBaseCost fallback.",
    )

    parser.add_argument(
        "--fallback-speed-kph",
        type=float,
        default=30.0,
        help="Fallback speed when GraphML lacks travel_time, speed_kph, and usable maxspeed.",
    )
    parser.add_argument(
        "--ignore-graph-travel-time",
        action="store_true",
        help="Recompute free-flow time from length and speed rather than using GraphML travel_time.",
    )
    parser.add_argument(
        "--include-unobserved-graph-edges",
        action="store_true",
        help="Include every graph edge in the free-flow lookup, not only observed edges.",
    )
    return parser.parse_args()


def normalize_scalar(value: Any) -> Any:
    """Convert GraphML strings containing serialized lists to Python values."""
    if not isinstance(value, str):
        return value
    text = value.strip()
    if text.startswith("[") and text.endswith("]"):
        try:
            return literal_ast.literal_eval(text)
        except Exception:
            return value
    return value


def numeric_values(value: Any) -> list[float]:
    value = normalize_scalar(value)
    if isinstance(value, (list, tuple, set)):
        out: list[float] = []
        for item in value:
            out.extend(numeric_values(item))
        return out
    if value is None:
        return []
    if isinstance(value, (int, float)):
        number = float(value)
        return [number] if math.isfinite(number) else []

    text = str(value).strip().lower()
    if not text:
        return []

    out: list[float] = []
    # Handle values such as "50", "50 mph", "30;50", "30|50".
    for token in re.findall(r"[-+]?\d+(?:\.\d+)?", text):
        number = float(token)
        if "mph" in text:
            number *= 1.609344
        if math.isfinite(number) and number > 0:
            out.append(number)
    return out


def first_positive(value: Any) -> float | None:
    values = numeric_values(value)
    return values[0] if values else None


def mean_positive(value: Any) -> float | None:
    values = numeric_values(value)
    return sum(values) / len(values) if values else None


def normalized_edge_key(value: Any) -> str:
    text = str(value).strip()
    try:
        number = float(text)
        if number.is_integer():
            return str(int(number))
    except Exception:
        pass
    return text


def edge_id(u: Any, v: Any, key: Any) -> str:
    return f"{normalized_edge_key(u)}|{normalized_edge_key(v)}|{normalized_edge_key(key)}"


def highway_classes(value: Any) -> list[str]:
    value = normalize_scalar(value)
    if isinstance(value, (list, tuple, set)):
        return [str(item).strip().lower() for item in value if str(item).strip()]
    if value is None:
        return []
    return [str(value).strip().lower()]


DEFAULT_HIGHWAY_SPEEDS_KPH = {
    "motorway": 100.0,
    "motorway_link": 60.0,
    "trunk": 80.0,
    "trunk_link": 50.0,
    "primary": 50.0,
    "primary_link": 40.0,
    "secondary": 50.0,
    "secondary_link": 40.0,
    "tertiary": 40.0,
    "tertiary_link": 35.0,
    "residential": 30.0,
    "unclassified": 30.0,
    "living_street": 20.0,
    "service": 20.0,
    "road": 30.0,
}


def infer_speed_kph(data: dict[str, Any], fallback_speed_kph: float) -> tuple[float, str]:
    speed = mean_positive(data.get("speed_kph"))
    if speed is not None:
        return speed, "speed_kph"

    speed = mean_positive(data.get("maxspeed"))
    if speed is not None:
        return speed, "maxspeed"

    classes = highway_classes(data.get("highway"))
    defaults = [
        DEFAULT_HIGHWAY_SPEEDS_KPH[item]
        for item in classes
        if item in DEFAULT_HIGHWAY_SPEEDS_KPH
    ]
    if defaults:
        return sum(defaults) / len(defaults), "highway_default"

    return fallback_speed_kph, "fallback_speed"


def load_graph_edge_info(
    graph_path: Path,
    fallback_speed_kph: float,
    prefer_graph_travel_time: bool,
) -> dict[str, GraphEdgeInfo]:
    print(f"Loading GraphML: {graph_path.resolve()}")
    graph = nx.read_graphml(graph_path, force_multigraph=True)

    info: dict[str, GraphEdgeInfo] = {}
    iterator = graph.edges(keys=True, data=True)

    for u, v, key, data in tqdm(
        iterator,
        total=graph.number_of_edges(),
        desc="Reading free-flow edges",
        unit="edge",
        dynamic_ncols=True,
    ):
        eid = edge_id(u, v, key)

        length_m = first_positive(data.get("length"))
        if length_m is None:
            length_m = first_positive(data.get("length_m"))
        if length_m is None:
            length_m = 0.0

        speed_kph, speed_source = infer_speed_kph(data, fallback_speed_kph)

        travel_time = first_positive(data.get("travel_time"))
        if travel_time is None:
            travel_time = first_positive(data.get("travel_time_sec"))

        if prefer_graph_travel_time and travel_time is not None:
            free_flow_sec = travel_time
            source = "graph_travel_time"
        elif length_m > 0 and speed_kph > 0:
            free_flow_sec = length_m / (speed_kph / 3.6)
            source = speed_source
        elif travel_time is not None:
            free_flow_sec = travel_time
            source = "graph_travel_time_fallback"
        else:
            free_flow_sec = 30.0
            source = "missing_length_fallback"

        # Java edgeBaseCost returns max(1.0, base).
        free_flow_sec = max(1.0, float(free_flow_sec))
        info[eid] = GraphEdgeInfo(
            edge_id=eid,
            length_m=max(0.0, float(length_m)),
            speed_kph=max(0.0, float(speed_kph)),
            free_flow_sec=free_flow_sec,
            source=source,
        )

    if not info:
        raise ValueError("No directed edges were loaded from the GraphML.")

    print(f"Graph edges loaded: {len(info):,}")
    return info


def inspect_input_columns(args: argparse.Namespace) -> None:
    header = pd.read_csv(args.input, nrows=0)
    required = {args.timestamp_column, args.edge_column, args.time_column}
    missing = required - set(header.columns)
    if missing:
        raise ValueError(
            f"Missing required columns: {sorted(missing)}; "
            f"available={list(header.columns)}"
        )


def normalize_unix_seconds(value: Any) -> float:
    timestamp = float(value)
    if not math.isfinite(timestamp):
        raise ValueError("Non-finite timestamp")
    if abs(timestamp) >= 100_000_000_000:
        timestamp /= 1000.0
    return timestamp


def scan_study_window(args: argparse.Namespace) -> tuple[StudyWindow, int]:
    first_date: date | None = None
    last_date: date | None = None
    total_rows = 0

    reader = pd.read_csv(
        args.input,
        usecols=[args.timestamp_column],
        chunksize=args.chunk_rows,
    )

    with tqdm(desc="Scanning dates", unit="row", dynamic_ncols=True) as progress:
        for chunk in reader:
            if args.limit > 0:
                remaining = args.limit - total_rows
                if remaining <= 0:
                    break
                if len(chunk) > remaining:
                    chunk = chunk.iloc[:remaining]

            total_rows += len(chunk)
            progress.update(len(chunk))

            values = pd.to_numeric(chunk[args.timestamp_column], errors="coerce")
            values = values[values.notna()].astype(float)
            if values.empty:
                continue

            ms = values.abs() >= 100_000_000_000
            values.loc[ms] /= 1000.0
            dates = pd.to_datetime(values, unit="s", utc=True).dt.date

            if not dates.empty:
                current_first = min(dates)
                current_last = max(dates)
                first_date = current_first if first_date is None else min(first_date, current_first)
                last_date = current_last if last_date is None else max(last_date, current_last)

    if total_rows == 0:
        raise ValueError("Input CSV contains no rows.")
    if first_date is None or last_date is None:
        raise ValueError("No valid Unix timestamps found.")

    retained_first = first_date + timedelta(days=args.warmup_days)
    if retained_first > last_date:
        raise ValueError(
            f"warmup-days={args.warmup_days} removes the complete sample "
            f"({first_date} through {last_date})."
        )

    start_epoch = datetime(
        retained_first.year,
        retained_first.month,
        retained_first.day,
        tzinfo=timezone.utc,
    ).timestamp()
    day_after_last = last_date + timedelta(days=1)
    end_epoch = datetime(
        day_after_last.year,
        day_after_last.month,
        day_after_last.day,
        tzinfo=timezone.utc,
    ).timestamp()

    return (
        StudyWindow(
            first_date=first_date,
            last_date=last_date,
            retained_first_date=retained_first,
            warmup_days=args.warmup_days,
            days_averaged=(last_date - retained_first).days + 1,
            start_epoch=start_epoch,
            end_epoch=end_epoch,
        ),
        total_rows,
    )


def split_sequence(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, float) and math.isnan(value):
        return []
    text = str(value).strip()
    if not text:
        return []
    return [part.strip() for part in text.split(EDGE_SEPARATOR) if part.strip()]


def init_worker(
    free_flow: dict[str, float],
    alpha: float,
    max_time_ratio: float,
    window_start: float,
    window_end: float,
    timestamp_column: str,
    edge_column: str,
    time_column: str,
) -> None:
    global _WORKER_FREE_FLOW, _WORKER_ALPHA, _WORKER_MAX_TIME_RATIO
    global _WORKER_WINDOW_START, _WORKER_WINDOW_END
    global _WORKER_TIMESTAMP_COLUMN, _WORKER_EDGE_COLUMN, _WORKER_TIME_COLUMN

    _WORKER_FREE_FLOW = free_flow
    _WORKER_ALPHA = alpha
    _WORKER_MAX_TIME_RATIO = max_time_ratio
    _WORKER_WINDOW_START = window_start
    _WORKER_WINDOW_END = window_end
    _WORKER_TIMESTAMP_COLUMN = timestamp_column
    _WORKER_EDGE_COLUMN = edge_column
    _WORKER_TIME_COLUMN = time_column


def process_chunk(
    records: list[dict[str, Any]],
) -> tuple[
    dict[tuple[str, int], tuple[int, float, float, float, int, int, int]],
    ChunkStats,
]:
    """
    Return edge-hour aggregates:
      count
      sum_observed_time
      sum_time_ratio
      sum_normalized_delay_power
      below_free_flow_count
      clipped_high_count
      missing_graph_count
    """
    aggregate: dict[
        tuple[str, int],
        list[float],
    ] = {}
    stats = ChunkStats(rows=len(records))

    for row in records:
        edges = split_sequence(row.get(_WORKER_EDGE_COLUMN))
        times_text = split_sequence(row.get(_WORKER_TIME_COLUMN))

        if not edges and not times_text:
            stats.empty_rows += 1
            continue
        if len(edges) != len(times_text):
            stats.mismatched_rows += 1
            continue

        try:
            start = normalize_unix_seconds(row.get(_WORKER_TIMESTAMP_COLUMN))
            durations = [float(value) for value in times_text]
        except (TypeError, ValueError, OverflowError):
            stats.malformed_rows += 1
            continue

        if any((not math.isfinite(value)) or value < 0.0 for value in durations):
            stats.invalid_time_rows += 1
            continue

        stats.valid_rows += 1
        cursor = start

        for eid, observed_sec in zip(edges, durations):
            edge_start = cursor
            cursor += observed_sec
            stats.traversals += 1

            if observed_sec <= 0.0:
                continue
            if not (_WORKER_WINDOW_START <= edge_start < _WORKER_WINDOW_END):
                continue

            hour = int(math.floor(edge_start / 3600.0)) % 24
            free_flow_sec = _WORKER_FREE_FLOW.get(eid)
            missing = 0

            if free_flow_sec is None:
                free_flow_sec = 30.0
                missing = 1
                stats.missing_graph_edges += 1

            raw_ratio = observed_sec / max(1e-9, free_flow_sec)
            ratio = min(max(raw_ratio, 0.0), _WORKER_MAX_TIME_RATIO)

            below = int(raw_ratio < 1.0)
            clipped = int(raw_ratio > _WORKER_MAX_TIME_RATIO)
            if below:
                stats.below_free_flow += 1
            if clipped:
                stats.clipped_high += 1

            # For t/t0 = 1 + alpha*x^beta, x^beta = (ratio-1)/alpha.
            normalized_delay_power = max(0.0, ratio - 1.0) / _WORKER_ALPHA

            key = (eid, hour)
            values = aggregate.get(key)
            if values is None:
                # count, obs_sum, ratio_sum, delay_power_sum,
                # below_count, clipped_count, missing_count
                values = [0.0] * 7
                aggregate[key] = values

            values[0] += 1.0
            values[1] += observed_sec
            values[2] += raw_ratio
            values[3] += normalized_delay_power
            values[4] += below
            values[5] += clipped
            values[6] += missing
            stats.retained_traversals += 1

    compact = {
        key: (
            int(values[0]),
            values[1],
            values[2],
            values[3],
            int(values[4]),
            int(values[5]),
            int(values[6]),
        )
        for key, values in aggregate.items()
    }
    return compact, stats


def chunk_records(args: argparse.Namespace) -> Iterator[list[dict[str, Any]]]:
    rows_seen = 0
    reader = pd.read_csv(
        args.input,
        usecols=[args.timestamp_column, args.edge_column, args.time_column],
        chunksize=args.chunk_rows,
    )

    for chunk in reader:
        if args.limit > 0:
            remaining = args.limit - rows_seen
            if remaining <= 0:
                break
            if len(chunk) > remaining:
                chunk = chunk.iloc[:remaining]

        rows_seen += len(chunk)
        yield chunk.to_dict(orient="records")


def merge_aggregates(
    destination: dict[tuple[str, int], list[float]],
    partial: dict[tuple[str, int], tuple[int, float, float, float, int, int, int]],
) -> None:
    for key, values in partial.items():
        current = destination.get(key)
        if current is None:
            destination[key] = [float(value) for value in values]
        else:
            for index, value in enumerate(values):
                current[index] += value


def aggregate_observations(
    args: argparse.Namespace,
    free_flow: dict[str, float],
    window: StudyWindow,
    total_rows: int,
) -> tuple[dict[tuple[str, int], list[float]], ChunkStats]:
    aggregate: dict[tuple[str, int], list[float]] = {}
    total_stats = ChunkStats()

    initializer_args = (
        free_flow,
        args.alpha,
        args.max_time_ratio,
        window.start_epoch,
        window.end_epoch,
        args.timestamp_column,
        args.edge_column,
        args.time_column,
    )

    if args.workers <= 1:
        init_worker(*initializer_args)
        with tqdm(
            total=total_rows,
            desc="Inferring congestion",
            unit="trip",
            dynamic_ncols=True,
        ) as progress:
            for records in chunk_records(args):
                partial, stats = process_chunk(records)
                merge_aggregates(aggregate, partial)
                total_stats.add(stats)
                progress.update(stats.rows)
        return aggregate, total_stats

    context = multiprocessing.get_context("spawn" if os.name == "nt" else "fork")
    with context.Pool(
        processes=args.workers,
        initializer=init_worker,
        initargs=initializer_args,
    ) as pool:
        iterator = pool.imap_unordered(
            process_chunk,
            chunk_records(args),
            chunksize=1,
        )
        with tqdm(
            total=total_rows,
            desc="Inferring congestion",
            unit="trip",
            dynamic_ncols=True,
        ) as progress:
            for partial, stats in iterator:
                merge_aggregates(aggregate, partial)
                total_stats.add(stats)
                progress.update(stats.rows)

    return aggregate, total_stats


def capacity_proxy(
    traversal_count: float,
    divisor: float,
    minimum: float,
    maximum: float,
) -> float:
    return max(minimum, min(maximum, traversal_count / divisor))


def build_background_output(
    args: argparse.Namespace,
    aggregate: dict[tuple[str, int], list[float]],
    graph_info: dict[str, GraphEdgeInfo],
    window: StudyWindow,
) -> tuple[pd.DataFrame, dict[str, int]]:
    total_count_by_edge: dict[str, int] = defaultdict(int)
    for (eid, _hour), values in aggregate.items():
        total_count_by_edge[eid] += int(values[0])

    rows: list[dict[str, Any]] = []

    for (eid, hour), values in aggregate.items():
        count = int(values[0])
        if count < args.min_observations:
            continue

        obs_sum = values[1]
        raw_ratio_sum = values[2]
        delay_power_sum = values[3]
        below_count = int(values[4])
        clipped_count = int(values[5])
        missing_count = int(values[6])

        edge_count = total_count_by_edge[eid]
        cap = capacity_proxy(
            edge_count,
            args.capacity_divisor,
            args.min_capacity,
            args.max_capacity,
        )

        mean_delay_power = delay_power_sum / count
        normalized_load = mean_delay_power ** (1.0 / args.beta)
        implied_total_flow = cap * normalized_load
        background_flow = max(0.0, implied_total_flow - args.self_vehicle_flow)

        edge = graph_info.get(eid)
        free_flow_sec = (
            edge.free_flow_sec if edge is not None else args.missing_free_flow_sec
        )

        rows.append(
            {
                "edge_id": eid,
                "hour_of_day": int(hour),
                "background_flow": background_flow,
                "volume_count": count,
                "exposure_sec": obs_sum,
                "days_averaged": window.days_averaged,
                "warmup_days_skipped": window.warmup_days,
                "mean_observed_time_sec": obs_sum / count,
                "free_flow_time_sec": free_flow_sec,
                "mean_time_ratio": raw_ratio_sum / count,
                "capacity_proxy": cap,
                "implied_total_flow": implied_total_flow,
                "self_vehicle_flow_subtracted": args.self_vehicle_flow,
                "below_free_flow_count": below_count,
                "clipped_high_count": clipped_count,
                "missing_graph_count": missing_count,
            }
        )

    result = pd.DataFrame(rows)
    if not result.empty:
        result = result.sort_values(
            ["edge_id", "hour_of_day"],
            kind="stable",
        ).reset_index(drop=True)

    return result, total_count_by_edge


def build_free_flow_lookup(
    args: argparse.Namespace,
    graph_info: dict[str, GraphEdgeInfo],
    total_count_by_edge: dict[str, int],
) -> pd.DataFrame:
    if args.include_unobserved_graph_edges:
        edge_ids: Iterable[str] = graph_info.keys()
    else:
        edge_ids = total_count_by_edge.keys()

    rows: list[dict[str, Any]] = []

    for eid in edge_ids:
        info = graph_info.get(eid)
        if info is None:
            length_m = 0.0
            speed_kph = 0.0
            free_flow_sec = args.missing_free_flow_sec
            source = "missing_graph_fallback"
        else:
            length_m = info.length_m
            speed_kph = info.speed_kph
            free_flow_sec = info.free_flow_sec
            source = info.source

        count = max(1, int(total_count_by_edge.get(eid, 0)))
        mean_time_per_km = (
            free_flow_sec / (length_m / 1000.0)
            if length_m > 0
            else float("nan")
        )

        rows.append(
            {
                "lookup_key": f"{eid}@all_day",
                "edge_id": eid,
                "hour_of_day_str": "all_day",
                "time_scope": "all_day",
                "is_fallback": 1,
                "edge_traversal_count": count,
                "total_length_m": length_m * count,
                "total_time_sec": free_flow_sec * count,
                "mean_length_m": length_m,
                "mean_traversal_time_sec": free_flow_sec,
                "mean_speed_kph": speed_kph,
                "mean_time_per_km_sec": mean_time_per_km,
                "free_flow_source": source,
            }
        )

    result = pd.DataFrame(rows)
    if not result.empty:
        result = result.sort_values("edge_id", kind="stable").reset_index(drop=True)
    return result


def write_csv(frame: pd.DataFrame, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    frame.to_csv(path, index=False)


def main() -> int:
    args = parse_arguments()
    started = time.perf_counter()

    if not args.input.exists():
        raise FileNotFoundError(args.input)
    if not args.graph.exists():
        raise FileNotFoundError(args.graph)
    if args.workers < 1:
        raise ValueError("--workers must be at least 1.")
    if args.chunk_rows < 1:
        raise ValueError("--chunk-rows must be positive.")
    if args.warmup_days < 0:
        raise ValueError("--warmup-days cannot be negative.")
    if args.alpha <= 0 or args.beta <= 0:
        raise ValueError("--alpha and --beta must be positive.")
    if args.capacity_divisor <= 0:
        raise ValueError("--capacity-divisor must be positive.")
    if args.min_capacity <= 0 or args.max_capacity < args.min_capacity:
        raise ValueError("Invalid capacity bounds.")
    if args.max_time_ratio <= 1:
        raise ValueError("--max-time-ratio must be greater than 1.")
    if args.missing_free_flow_sec <= 0:
        raise ValueError("--missing-free-flow-sec must be positive.")

    inspect_input_columns(args)

    graph_info = load_graph_edge_info(
        graph_path=args.graph,
        fallback_speed_kph=args.fallback_speed_kph,
        prefer_graph_travel_time=not args.ignore_graph_travel_time,
    )
    free_flow = {
        eid: info.free_flow_sec
        for eid, info in graph_info.items()
    }

    window, total_rows = scan_study_window(args)
    print(
        f"Study dates: {window.first_date} through {window.last_date}; "
        f"retained from {window.retained_first_date}; "
        f"days averaged={window.days_averaged}"
    )

    aggregate, stats = aggregate_observations(
        args=args,
        free_flow=free_flow,
        window=window,
        total_rows=total_rows,
    )

    background, total_count_by_edge = build_background_output(
        args=args,
        aggregate=aggregate,
        graph_info=graph_info,
        window=window,
    )
    free_flow_lookup = build_free_flow_lookup(
        args=args,
        graph_info=graph_info,
        total_count_by_edge=total_count_by_edge,
    )

    write_csv(background, args.output)
    write_csv(free_flow_lookup, args.free_flow_output)

    elapsed = time.perf_counter() - started
    skipped = (
        stats.empty_rows
        + stats.mismatched_rows
        + stats.malformed_rows
        + stats.invalid_time_rows
    )

    print(f"Background output: {args.output.resolve()}")
    print(f"Free-flow lookup: {args.free_flow_output.resolve()}")
    print(f"Background edge-hour rows: {len(background):,}")
    print(f"Free-flow edge rows: {len(free_flow_lookup):,}")
    print(f"Valid trips: {stats.valid_rows:,}/{stats.rows:,}")
    print(f"Skipped trips: {skipped:,}")
    print(f"Retained traversals: {stats.retained_traversals:,}")
    print(f"Missing graph edge observations: {stats.missing_graph_edges:,}")
    print(f"Below-free-flow observations: {stats.below_free_flow:,}")
    print(f"High-ratio observations clipped: {stats.clipped_high:,}")
    print(f"Runtime: {elapsed / 60.0:.2f} minutes")

    if stats.missing_graph_edges:
        print(
            "WARNING: some realized edge IDs were not found in the GraphML. "
            "Check that the map-matching and free-flow graph are identical.",
            file=sys.stderr,
        )

    return 0


if __name__ == "__main__":
    multiprocessing.freeze_support()
    raise SystemExit(main())
