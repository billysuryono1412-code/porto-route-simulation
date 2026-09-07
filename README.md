# Porto Route-Choice and Rerouting Simulation

An end-to-end research project for studying behavioral route choice and adaptive rerouting using Porto taxi trajectories. Python scripts transform raw GPS trajectories and an OpenStreetMap road network into simulation-ready inputs; a Java 17 engine then models persistent taxis, congestion, route choice, memory, edge familiarity, and optional rerouting.

## Research question

Can a behavior-aware routing model reproduce observed route shares, edge flows, and travel times more closely than conventional shortest- or fastest-path strategies?

The project evaluates four routing strategies:

- `behavioral`: balances travel time, memory, edge familiarity, detour, and route complexity.
- `shortest`: selects the shortest available route.
- `static_fastest`: selects the fastest route using fixed reference costs.
- `dynamic_fastest`: selects or updates routes using simulated traffic conditions.

## Final experimental result

In the final 20-seed replication, the behavioral no-reroute configuration achieved **3.91% lower mean objective loss than dynamic-fastest routing**. Each run simulated 5,000 trips, producing 500,000 trip evaluations across the compared configurations.

The objective combines:

- route-share Jensen–Shannon divergence;
- edge-flow weighted mean absolute error;
- travel-time weighted mean absolute error; and
- novelty-rate error.

Lower objective loss indicates closer agreement with the observed reference data. This result applies to the tested Porto network, demand construction, candidate-route set, and objective weighting; it should not be interpreted as evidence that disabling rerouting is universally optimal.

## System overview

1. Map-match Porto taxi GPS trajectories to directed road edges.
2. Estimate free-flow travel times and hourly background congestion.
3. Aggregate observed trips into origin–destination route shares.
4. Generate graph-based candidate routes and waypoint files.
5. Convert the GraphML road network into rerouting graphs.
6. Run route-choice sweeps, benchmarks, and multi-seed replications in Java.
7. Compare simulated outcomes with observed route, flow, and travel-time statistics.

## Repository structure

```text
.
├── preprocessing/
│   ├── 1_porto_map_match.py
│   ├── 2_infer_background_from_edge_times.py
│   ├── 3_build_od_route_shares.py
│   ├── 4_generate_candidate_paths.py
│   ├── 5_graphml_to_reroute_csv.py
│   ├── run_all_pipeline.py
│   ├── data/
│   └── README.md
├── simulation/
│   ├── src/main/java/porto/sweep/
│   ├── examples/
│   ├── testdata/
│   ├── pom.xml
│   └── README.md
├── LICENSE
└── README.md
```

See [`preprocessing/README.md`](preprocessing/README.md) for the complete data pipeline and [`simulation/README.md`](simulation/README.md) for simulator configuration, evaluation metrics, and output schemas.

## Quick start

The quick start is a small smoke test using the derived inputs already included under `simulation/examples/`. It verifies that the project compiles, loads the data, simulates 50 trips, and writes results. It is not the calibrated experiment reported above.

### Requirements

- JDK 17 or newer
- Maven 3.8 or newer
- Git

Clone the repository:

```bash
git clone https://github.com/billysuryono1412-code/porto-route-simulation.git
```

Enter the Java project:

```bash
cd porto-route-simulation/simulation
```

Compile and run the demo:

```bash
mvn -q compile exec:java "-Dexec.args=--config=examples/quickstart.properties"
```

Results are written to a timestamped directory under `simulation/examples/quickstart_runs/`. Initial loading can take longer than the 50-trip simulation because the repository includes the full derived network inputs.

## Preprocessing

The complete Python pipeline requires the original Porto taxi trajectory data and a Porto road-network GraphML file. From the `preprocessing` directory, install the documented dependencies and run:

```bash
python run_all_pipeline.py --root .
```

The five stages can also be run independently. The raw taxi dataset is not committed; derived simulation inputs are included so the Java demo can run without repeating the full map-matching process.

## Simulation outputs

Depending on the selected experiment mode, the Java runner produces:

- per-run parameters and evaluation metrics;
- trip-level realized paths and travel times;
- best and top-ranked configurations;
- benchmark design files; and
- replication-level summaries across random seeds.

The simulator supports grid search, Latin hypercube sampling, routing-mode benchmarks, and replicated finalist evaluation.

## Technology

- **Python:** pandas, NumPy, OSMnx, NetworkX, Shapely, Leuven Map Matching
- **Java 17:** simulation engine, route evaluation, parameter sweeps, and concurrent execution
- **Maven:** Java compilation and execution
- **Data:** Porto taxi trajectories and OpenStreetMap-derived road networks

## Limitations

This is a research prototype rather than a microscopic traffic simulator. Observed routes contribute to demand construction and evaluation, network coverage is incomplete, and conclusions depend on the candidate-route generation method and objective weights. The replicated comparison reduces seed sensitivity but does not constitute held-out validation on another city.

## Data attribution and license

The source code is released under the [MIT License](LICENSE). Porto taxi data remains subject to the terms of its original publisher. Road-network data is derived from [OpenStreetMap](https://www.openstreetmap.org/) and is subject to the Open Database License; attribution belongs to OpenStreetMap contributors.
