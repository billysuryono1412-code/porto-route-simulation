from __future__ import annotations

import argparse
import math
from pathlib import Path
from typing import Any

import networkx as nx
import pandas as pd


def to_float(value: Any, field_name: str, edge_id: str) -> float:
    """Convert a GraphML edge attribute to a finite float."""
    if value is None:
        raise ValueError(f"Edge {edge_id} is missing required attribute '{field_name}'.")

    # Handle ordinary numbers and numeric strings.
    try:
        number = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(
            f"Edge {edge_id} has a non-numeric '{field_name}' value: {value!r}"
        ) from exc

    if not math.isfinite(number):
        raise ValueError(
            f"Edge {edge_id} has a non-finite '{field_name}' value: {value!r}"
        )
    return number


def load_edges(graphml_path: Path) -> pd.DataFrame:
    """Load all directed GraphML edges in their stored iteration order."""
    graph = nx.read_graphml(
        graphml_path,
        node_type=int,
        edge_key_type=int,
        force_multigraph=True,
    )

    rows: list[dict[str, Any]] = []

    for from_node, to_node, key, data in graph.edges(keys=True, data=True):
        edge_id = f"{from_node}|{to_node}|{key}"

        # OSMnx normally stores these as 'length' and 'travel_time'.
        # The *_m / *_sec alternatives are also accepted.
        length_raw = data.get("length", data.get("length_m"))
        travel_time_raw = data.get("travel_time", data.get("travel_time_sec"))

        length_m = to_float(length_raw, "length", edge_id)

        if travel_time_raw is not None:
            travel_time_sec = to_float(travel_time_raw, "travel_time", edge_id)
        else:
            # Fallback when the GraphML has speed_kph but no travel_time.
            speed_kph = to_float(data.get("speed_kph"), "speed_kph", edge_id)
            if speed_kph <= 0:
                raise ValueError(f"Edge {edge_id} has speed_kph <= 0: {speed_kph}")
            travel_time_sec = length_m / (speed_kph / 3.6)

        rows.append(
            {
                "from_node": from_node,
                "to_node": to_node,
                "edge_id": edge_id,
                "length_m": length_m,
                "travel_time_sec": travel_time_sec,
            }
        )

    if not rows:
        raise ValueError(f"No edges were found in {graphml_path}")

    return pd.DataFrame(
        rows,
        columns=[
            "from_node",
            "to_node",
            "edge_id",
            "length_m",
            "travel_time_sec",
        ],
    )


def write_graph_csvs(
    edges: pd.DataFrame,
    length_output: Path,
    time_output: Path,
) -> None:
    """Write CSVs with the exact structure shown in the supplied samples."""
    length_output.parent.mkdir(parents=True, exist_ok=True)
    time_output.parent.mkdir(parents=True, exist_ok=True)

    length_df = edges.copy()
    length_df["weight"] = length_df["length_m"]
    length_df["graph_type"] = "length"

    time_df = edges.copy()
    time_df["weight"] = time_df["travel_time_sec"]
    time_df["graph_type"] = "time"

    # index=True intentionally creates the blank first header column shown
    # in sample_length.csv and sample_time.csv.
    length_df.to_csv(length_output, index=True)
    time_df.to_csv(time_output, index=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Convert an OSM/Porto GraphML road network into separate "
            "length-weighted and time-weighted edge CSV files."
        )
    )
    parser.add_argument("graphml", type=Path, help="Input Porto .graphml file")
    parser.add_argument(
        "--length-output",
        type=Path,
        default=Path("porto_length.csv"),
        help="Output length-weighted CSV (default: porto_length.csv)",
    )
    parser.add_argument(
        "--time-output",
        type=Path,
        default=Path("porto_time.csv"),
        help="Output time-weighted CSV (default: porto_time.csv)",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    if not args.graphml.is_file():
        raise FileNotFoundError(f"GraphML file not found: {args.graphml}")

    edges = load_edges(args.graphml)
    write_graph_csvs(edges, args.length_output, args.time_output)

    print(f"Loaded {len(edges):,} directed edges")
    print(f"Length graph: {args.length_output.resolve()}")
    print(f"Time graph:   {args.time_output.resolve()}")


if __name__ == "__main__":
    main()
