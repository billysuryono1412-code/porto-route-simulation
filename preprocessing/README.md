# Porto Taxi Route Preprocessing Pipeline

This project converts the Porto taxi trajectory dataset and an OSM road-network GraphML into the files required by the Java route-choice and rerouting simulator.

The workflow performs five stages:

1. Map-match raw taxi GPS trajectories to directed road edges.
2. Infer hourly background traffic and generate a free-flow edge lookup.
3. Aggregate observed trips into OD-specific route shares.
4. Generate realistic candidate routes and route waypoints.
5. Convert the GraphML network into length- and time-weighted rerouting CSVs.

`run_all_pipeline.py` runs the stages in dependency order, writes a combined log, records machine-readable status, and can send Telegram notifications while long-running stages execute.

---

## Pipeline flow

```text
data/porto_train.csv
        │
        ▼
1_porto_map_match.py
        │
        ├── outputs/porto_train_edge_mapmatched.csv
        └── logs/map_matching_diagnostics.csv
                    │
                    ├──────────────────────────────┐
                    ▼                              ▼
2_infer_background_from_edge_times.py   3_build_od_route_shares.py
        │                              │
        ├── background_congestion_flow.csv         └── od_route_share.csv
        └── sim_edge_lookup_freeflow.csv                     │
                                                             ▼
                                                4_generate_candidate_paths.py
                                                             │
                                                             ├── candidate_routes.csv
                                                             └── route_waypoints.csv

data/porto_full_drive.graphml
        │
        └── 5_graphml_to_reroute_csv.py
                ├── length_network.csv
                └── time_network.csv
```

Stages 2 and 3 both depend on the map-matched output from stage 1. Stage 4 depends on the OD route-share output from stage 3. Stage 5 is independent of stages 1–4, but the pipeline runs it last for convenience.

---

## Expected project structure

```text
porto_preprocess/
│   1_porto_map_match.py
│   2_infer_background_from_edge_times.py
│   3_build_od_route_shares.py
│   4_generate_candidate_paths.py
│   5_graphml_to_reroute_csv.py
│   run_all_pipeline.py
│   README.md
│
├── data/
│   ├── porto_train.csv
│   └── porto_full_drive.graphml
│
├── logs/
│   ├── map_matching_diagnostics.csv
│   ├── pipeline_status.json
│   └── pipeline_YYYYMMDD_HHMMSS.log
│
└── outputs/
    ├── porto_train_edge_mapmatched.csv
    ├── background_congestion_flow.csv
    ├── sim_edge_lookup_freeflow.csv
    ├── od_route_share.csv
    ├── candidate_routes.csv
    ├── route_waypoints.csv
    ├── length_network.csv
    └── time_network.csv
```

The runner creates `outputs/` and `logs/` automatically when they do not exist.

---

## Requirements

- Windows, Linux, or macOS
- Python 3.10 or newer recommended
- Enough storage for the raw dataset, GraphML, map-matched output, and diagnostics
- Substantial RAM for multiprocessing map matching
- Internet access only when Telegram notifications are enabled or when stage 1 must download a missing graph

Install the Python dependencies:

```powershell
python -m pip install "pandas>=2.0" "numpy>=1.24" "osmnx>=2.0,<3" "leuvenmapmatching>=1.1.4" "shapely>=2.0" "pyproj>=3.6" "tqdm>=4.66" "rtree>=1.2" "networkx>=3.2"
```

A Conda environment is recommended for the full map-matching run:

```powershell
conda create -n porto-route python=3.11 -y; conda activate porto-route; python -m pip install "pandas>=2.0" "numpy>=1.24" "osmnx>=2.0,<3" "leuvenmapmatching>=1.1.4" "shapely>=2.0" "pyproj>=3.6" "tqdm>=4.66" "rtree>=1.2" "networkx>=3.2"
```

---

## Input data

### `data/porto_train.csv`

The map matcher expects at least these columns:

| Column | Description |
|---|---|
| `TRIP_ID` | Unique trip identifier |
| `TAXI_ID` | Taxi identifier |
| `TIMESTAMP` | Unix timestamp for the first observation |
| `POLYLINE` | JSON list of `[longitude, latitude]` points |

The GPS sampling interval defaults to 15 seconds.

### `data/porto_full_drive.graphml`

The GraphML should be a directed Porto road network with node coordinates and edge attributes such as:

- `length`
- `travel_time`, or enough speed information to infer it
- `speed_kph` or `maxspeed` when `travel_time` is absent
- optional road-class and geometry attributes

Use the same GraphML for map matching, background inference, route generation, and rerouting-network conversion. Mixing graphs can create edge IDs that cannot be matched later.

---

# Quick start

Open PowerShell in the project directory:

```powershell
cd D:\porto\porto_preprocess
```

Run the complete pipeline without Telegram:

```powershell
python .\run_all_pipeline.py --root .
```

Run with Telegram notifications:

```powershell
$env:TELEGRAM_BOT_TOKEN="YOUR_BOT_TOKEN"; $env:TELEGRAM_CHAT_ID="YOUR_CHAT_ID"; python .\run_all_pipeline.py --root .
```

The Telegram environment variables apply to the current PowerShell session. They do not need to be written into the source code.

---

## Telegram configuration

The runner reads:

```text
TELEGRAM_BOT_TOKEN
TELEGRAM_CHAT_ID
```

It sends notifications when:

- the pipeline starts;
- a stage starts;
- a stage finishes;
- selected important output lines appear;
- a heartbeat interval is reached;
- the pipeline completes, fails, or is cancelled.

Telegram is optional by default. A Telegram error is printed as a warning but does not stop preprocessing.

Make Telegram mandatory:

```powershell
python .\run_all_pipeline.py --root . --telegram-required
```

Change the heartbeat interval from the default 15 minutes:

```powershell
python .\run_all_pipeline.py --root . --heartbeat-minutes 10
```

Disable heartbeat messages while retaining start and completion messages:

```powershell
python .\run_all_pipeline.py --root . --heartbeat-minutes 0
```

Credentials can also be supplied directly, although environment variables are safer:

```powershell
python .\run_all_pipeline.py --root . --bot-token "YOUR_BOT_TOKEN" --chat-id "YOUR_CHAT_ID"
```

Do not commit bot tokens to Git.

---

# Running the pipeline

## Validate commands without executing

```powershell
python .\run_all_pipeline.py --root . --dry-run
```

The dry run checks the selected stage dependencies and prints the exact commands that would be launched.

## Small test run

Before processing the full dataset, test the first stages on a small number of rows:

```powershell
python .\run_all_pipeline.py --root . --limit 1000
```

`--limit` is passed directly to stages 1 and 2. Because stage 3 reads the stage-1 output, a fresh limited run also limits the downstream OD data.

Use separate test output directories or back up full outputs before testing over existing files.

## Run only selected stages

Run stages 3 through 5:

```powershell
python .\run_all_pipeline.py --root . --from-step 3 --to-step 5
```

Run only candidate-route generation:

```powershell
python .\run_all_pipeline.py --root . --from-step 4 --to-step 4
```

Run only GraphML conversion:

```powershell
python .\run_all_pipeline.py --root . --from-step 5 --to-step 5
```

## Resume by skipping existing outputs

```powershell
python .\run_all_pipeline.py --root . --skip-existing
```

A stage is skipped only when all of its expected output files exist and are non-empty.

`--skip-existing` does not inspect the contents for correctness. Delete or rename invalid output files before resuming.

## Select worker counts

```powershell
python .\run_all_pipeline.py --root . --map-workers 4 --background-workers 8 --candidate-workers 4
```

The defaults are deliberately conservative:

- map matching: up to 4 workers;
- background inference: up to 8 workers;
- candidate generation: up to 4 workers.

Each map-matching worker loads its own graph and spatial index. Increasing workers can raise memory use significantly.

## Use a specific Python environment

```powershell
python .\run_all_pipeline.py --root . --python "C:\Users\Billy\miniconda3\envs\porto-route\python.exe"
```

## Custom log path

```powershell
python .\run_all_pipeline.py --root . --log-file logs\my_pipeline_run.log
```

---

# Pipeline stages

## Stage 1 — GPS map matching

Script:

```text
1_porto_map_match.py
```

Purpose:

- parses Porto `POLYLINE` observations;
- removes short implausible-speed GPS excursions;
- map-matches cleaned points to the directed GraphML network;
- repairs disconnected matched-node pairs using shortest paths;
- allocates observed trip time across the matched edges;
- writes success and failure diagnostics.

Pipeline command equivalent:

```powershell
python .\1_porto_map_match.py --input .\data\porto_train.csv --output .\outputs\porto_train_edge_mapmatched.csv --diagnostics .\logs\map_matching_diagnostics.csv --graph .\data\porto_full_drive.graphml --workers 4 --chunksize 8 --merge-input-columns
```

Primary outputs:

```text
outputs/porto_train_edge_mapmatched.csv
logs/map_matching_diagnostics.csv
```

Important output columns:

| Column | Format |
|---|---|
| `realized_edge_seq` | `u|v|key;u|v|key;...` |
| `realized_edge_travel_sec_seq` | `seconds;seconds;...` |
| `realized_node_seq` | `node;node;node;...` |

The edge and edge-time sequences must have equal lengths. A successful node sequence must contain exactly one more node than edges.

### Diagnostics

The diagnostics file includes fields such as:

- `status`
- raw and cleaned point counts
- removed observation indices
- matched node and edge counts
- observed and allocated durations
- duration allocation error
- route progress
- mean and maximum snap distance
- failure message

A failed trip remains represented in the main output with empty realized sequences and a corresponding `status=failed` diagnostic row.

---

## Stage 2 — Background traffic and free-flow lookup

Script:

```text
2_infer_background_from_edge_times.py
```

Purpose:

- reads realized edge travel times from stage 1;
- loads edge length and free-flow information from the GraphML;
- groups traversals by edge and hour of day;
- inverts the Java congestion equation to estimate background flow;
- produces a free-flow lookup for the simulator.

Pipeline command equivalent:

```powershell
python .\2_infer_background_from_edge_times.py --input .\outputs\porto_train_edge_mapmatched.csv --graph .\data\porto_full_drive.graphml --output .\outputs\background_congestion_flow.csv --free-flow-output .\outputs\sim_edge_lookup_freeflow.csv --workers 8 --warmup-days 5
```

Primary outputs:

```text
outputs/background_congestion_flow.csv
outputs/sim_edge_lookup_freeflow.csv
```

The background-flow calculation uses the Java-style congestion model:

```text
t = t0 × [1 + alpha × (flow / capacity)^beta]
```

Default parameters include `alpha=0.5`, `beta=2.0`, and a capacity proxy derived from edge traversal count. The observed vehicle is subtracted by default so that the output represents background flow while the Java simulation supplies live flow.

Do not use an already-congested observed-time lookup together with the inferred background flow. Use `sim_edge_lookup_freeflow.csv` as the Java `edgeLookup` to avoid double-counting congestion.

---

## Stage 3 — Observed OD route shares

Script:

```text
3_build_od_route_shares.py
```

Purpose:

- groups identical ordered realized edge sequences;
- identifies origin and destination nodes;
- counts trips per route and OD;
- calculates route shares and stable normalized shares;
- optionally uses GraphML edge lengths and node coordinates for distance, speed, and circuity;
- uses SQLite internally so large route aggregations do not need to remain entirely in memory.

Pipeline command equivalent:

```powershell
python .\3_build_od_route_shares.py --input .\outputs\porto_train_edge_mapmatched.csv --output .\outputs\od_route_share.csv --graph .\data\porto_full_drive.graphml --min-route-trips 2
```

Primary output:

```text
outputs/od_route_share.csv
```

Important output columns include:

- `od_id`
- `route_id`
- `trip_count`
- `avg_time_sec`
- `avg_distance_m`
- `avg_speed_kph`
- `avg_circuity`
- `sample_start_node`
- `sample_end_node`
- `sample_route_node_seq`
- `sample_edge_seq`
- `share`
- `stable_share_norm`
- `route_rank`

Routes observed fewer than `--min-route-trips` are dropped. The default is 2 trips.

---

## Stage 4 — Candidate route generation

Script:

```text
4_generate_candidate_paths.py
```

Purpose:

- reads unique OD pairs from `od_route_share.csv`;
- creates fastest and shortest-path anchors;
- generates corridor-aware, penalized, stochastic, and via-node alternatives;
- filters excessive route stretch and overlap;
- writes candidate routes and waypoint coordinates.

Pipeline command equivalent:

```powershell
python .\4_generate_candidate_paths.py --graph .\data\porto_full_drive.graphml --ods .\outputs\od_route_share.csv --routes-out .\outputs\candidate_routes.csv --waypoints-out .\outputs\route_waypoints.csv --workers 4 --max-candidates 15
```

Primary outputs:

```text
outputs/candidate_routes.csv
outputs/route_waypoints.csv
```

Candidate families may include:

- `fastest`
- `shortest`
- `corridor`
- `penalty`
- `stochastic`
- `via`

### Required sequence format

The corrected script uses semicolons between sequence items:

```text
route_node_seq = 100;200;300
edge_seq       = 100|200|0;200|300|0
```

Within each edge ID, vertical bars separate `from_node`, `to_node`, and the GraphML edge key. Semicolons separate consecutive edges.

This format is required by the Java `PipeSeq.split()` loader.

---

## Stage 5 — Rerouting graph conversion

Script:

```text
5_graphml_to_reroute_csv.py
```

Purpose:

- loads every directed GraphML edge;
- extracts edge length;
- extracts `travel_time`, or derives it from `speed_kph`;
- writes separate length- and time-weighted graph CSVs for Java rerouting.

Pipeline command equivalent:

```powershell
python .\5_graphml_to_reroute_csv.py .\data\porto_full_drive.graphml --length-output .\outputs\length_network.csv --time-output .\outputs\time_network.csv
```

Primary outputs:

```text
outputs/length_network.csv
outputs/time_network.csv
```

Both files include:

- `from_node`
- `to_node`
- `edge_id`
- `length_m`
- `travel_time_sec`
- `weight`
- `graph_type`

The length file uses `length_m` as `weight`. The time file uses `travel_time_sec` as `weight`.

---

# Output files and Java configuration

Use the generated files in `sweep.properties` as follows:

```properties
candidateRoutes=D:\porto\porto_preprocess\outputs\candidate_routes.csv
routeWaypoints=D:\porto\porto_preprocess\outputs\route_waypoints.csv
edgeLookup=D:\porto\porto_preprocess\outputs\sim_edge_lookup_freeflow.csv
stableRoutes=D:\porto\porto_preprocess\outputs\od_route_share.csv
rerouteTimeGraphCsv=D:\porto\porto_preprocess\outputs\time_network.csv
rerouteLengthGraphCsv=D:\porto\porto_preprocess\outputs\length_network.csv
backgroundTrafficCsv=D:\porto\porto_preprocess\outputs\background_congestion_flow.csv
```

Windows Java property files accept escaped backslashes as shown above. Forward slashes may also be used:

```properties
candidateRoutes=D:/porto/porto_preprocess/outputs/candidate_routes.csv
```

Recommended mapping:

| Java property | Generated file |
|---|---|
| `candidateRoutes` | `outputs/candidate_routes.csv` |
| `routeWaypoints` | `outputs/route_waypoints.csv` |
| `edgeLookup` | `outputs/sim_edge_lookup_freeflow.csv` |
| `stableRoutes` | `outputs/od_route_share.csv` |
| `rerouteTimeGraphCsv` | `outputs/time_network.csv` |
| `rerouteLengthGraphCsv` | `outputs/length_network.csv` |
| `backgroundTrafficCsv` | `outputs/background_congestion_flow.csv` |

---

# Repair an already-generated candidate route file

Older candidate-route output may contain flattened pipe-separated sequences such as:

```text
route_node_seq = 100|200|300
edge_seq       = 100|200|0|200|300|0
```

The Java loader requires:

```text
route_node_seq = 100;200;300
edge_seq       = 100|200|0;200|300|0
```

Rerunning candidate generation can be expensive. The following Jupyter cell writes a corrected copy without replacing or locking the original file:

```python
from pathlib import Path
import csv

source = Path(r"D:\porto\porto_preprocess\outputs\candidate_routes.csv")
output = source.with_name("candidate_routes_fixed.csv")


def fix_nodes(text):
    text = str(text or "").strip()
    if not text or ";" in text:
        return text
    return ";".join(part.strip() for part in text.split("|") if part.strip())


def fix_edges(text):
    text = str(text or "").strip()
    if not text or ";" in text:
        return text

    parts = [part.strip() for part in text.split("|") if part.strip()]
    if len(parts) % 3 != 0:
        raise ValueError(
            f"Edge sequence has {len(parts)} components; expected a multiple of 3."
        )

    return ";".join(
        "|".join(parts[index:index + 3])
        for index in range(0, len(parts), 3)
    )


rows_read = 0
rows_changed = 0

with source.open("r", encoding="utf-8-sig", newline="") as src, \
     output.open("w", encoding="utf-8", newline="") as dst:

    reader = csv.DictReader(src)
    if not reader.fieldnames:
        raise ValueError("The input CSV has no header.")

    required = {"route_node_seq", "edge_seq"}
    missing = required - set(reader.fieldnames)
    if missing:
        raise ValueError(f"Missing columns: {sorted(missing)}")

    writer = csv.DictWriter(dst, fieldnames=reader.fieldnames)
    writer.writeheader()

    for line_number, row in enumerate(reader, start=2):
        rows_read += 1

        old_nodes = row.get("route_node_seq", "")
        old_edges = row.get("edge_seq", "")
        new_nodes = fix_nodes(old_nodes)
        new_edges = fix_edges(old_edges)

        node_count = len(new_nodes.split(";")) if new_nodes else 0
        edge_count = len(new_edges.split(";")) if new_edges else 0

        if edge_count and node_count != edge_count + 1:
            raise ValueError(
                f"Line {line_number}: nodes={node_count}, edges={edge_count}; "
                "expected nodes = edges + 1."
            )

        row["route_node_seq"] = new_nodes
        row["edge_seq"] = new_edges

        if new_nodes != old_nodes or new_edges != old_edges:
            rows_changed += 1

        writer.writerow(row)

print("Repair completed successfully.")
print(f"Rows read:    {rows_read:,}")
print(f"Rows changed: {rows_changed:,}")
print(f"Fixed file:   {output}")
```

Then update the Java property:

```properties
candidateRoutes=D:\porto\porto_preprocess\outputs\candidate_routes_fixed.csv
```

Keep the original and any timestamped backup until the Java simulator successfully loads and validates the corrected file.

---

# Logs and status

## Combined pipeline log

Every run creates:

```text
logs/pipeline_YYYYMMDD_HHMMSS.log
```

The log contains:

- the exact command for every stage;
- combined standard output and standard error;
- progress output captured from child scripts;
- stage completion messages;
- errors immediately before a failed stage stops the pipeline.

## Machine-readable status

The runner maintains:

```text
logs/pipeline_status.json
```

Possible status values include:

- `starting`
- `running`
- `completed`
- `cancelled`
- `failed`

During a running stage, the file includes the stage number, stage name, child-process ID, start time, and command. At completion or failure, it includes elapsed time and completed or skipped stages.

## Cancelling safely

Press:

```text
Ctrl+C
```

The runner attempts to terminate the complete child process tree. This is important on Windows because the preprocessing scripts may have spawned multiple worker processes.

---

# Performance guidance

## Map matching

Map matching is normally the longest stage. Each worker loads an independent graph and Leuven spatial index.

For a 32 GB machine, start with:

```powershell
python .\run_all_pipeline.py --root . --map-workers 4 --map-chunksize 8
```

Reduce `--map-workers` to 2 or 3 when:

- RAM usage approaches the system limit;
- the machine begins paging heavily;
- each worker's graph initialization consumes too much memory;
- interactive responsiveness becomes poor.

Increase worker count only after observing stable memory use.

## Background inference

This stage processes rows in chunks and generally tolerates more workers than map matching:

```powershell
python .\run_all_pipeline.py --root . --from-step 2 --to-step 2 --background-workers 8
```

## Candidate generation

Each candidate-generation worker also loads the graph once. Four workers is a reasonable starting point on a 32 GB machine:

```powershell
python .\run_all_pipeline.py --root . --from-step 4 --to-step 4 --candidate-workers 4
```

Fewer workers reduce peak memory. More workers may improve throughput when memory and disk bandwidth permit.

---

# Recovery and reruns

## A stage failed

1. Read the last lines of the timestamped pipeline log.
2. Inspect `logs/pipeline_status.json`.
3. Fix the reported input, dependency, or permission issue.
4. Restart from the failed stage using `--from-step`.

Example: restart from background inference:

```powershell
python .\run_all_pipeline.py --root . --from-step 2 --to-step 5
```

## An output file is open or locked on Windows

Windows may raise:

```text
PermissionError: [WinError 5] Access is denied
```

Close programs that may hold the CSV open, including:

- Excel;
- IntelliJ IDEA's table viewer;
- another Jupyter kernel;
- the Java simulator;
- a Python process still reading the file;
- file preview software.

For repairs, writing a new file such as `candidate_routes_fixed.csv` is safer than replacing the original.

## A stage completed but produced an invalid file

Do not use `--skip-existing` for that stage. Rename or delete its expected output first, then rerun the stage.

Example:

```powershell
Rename-Item .\outputs\candidate_routes.csv candidate_routes_invalid.csv; python .\run_all_pipeline.py --root . --from-step 4 --to-step 4
```

## Telegram stopped working

Without `--telegram-required`, Telegram failure does not stop preprocessing. Check the terminal or log for the first Telegram warning, then verify:

- bot token;
- chat ID;
- network access;
- whether the user has started a conversation with the bot;
- firewall or proxy restrictions.

---

# Common validation checks

## Map-matched sequences

For every successful trip:

```text
number of realized edges = number of realized edge travel times
number of realized nodes = number of realized edges + 1
```

## Candidate-route sequences

For every candidate:

```text
number of route nodes = number of edges + 1
```

Correct example:

```text
route_node_seq = A;B;C;D
edge_seq       = A|B|0;B|C|0;C|D|0
```

## Graph consistency

The edge ID convention throughout the pipeline is:

```text
from_node|to_node|edge_key
```

When stage 2 reports missing GraphML edge observations, confirm that stage 1 and stage 2 are using the identical GraphML file.

## Free-flow consistency

Use:

```text
outputs/sim_edge_lookup_freeflow.csv
```

as the simulator's `edgeLookup` when background traffic is enabled. Using observed congested edge means together with inferred background flow can apply congestion twice.

---

# Useful commands

Full production run with Telegram:

```powershell
$env:TELEGRAM_BOT_TOKEN="YOUR_BOT_TOKEN"; $env:TELEGRAM_CHAT_ID="YOUR_CHAT_ID"; python .\run_all_pipeline.py --root . --map-workers 4 --background-workers 8 --candidate-workers 4
```

Validate everything without running:

```powershell
python .\run_all_pipeline.py --root . --dry-run
```

Small test:

```powershell
python .\run_all_pipeline.py --root . --limit 1000 --heartbeat-minutes 5
```

Resume while skipping non-empty completed outputs:

```powershell
python .\run_all_pipeline.py --root . --skip-existing
```

Rerun OD shares and candidate generation:

```powershell
python .\run_all_pipeline.py --root . --from-step 3 --to-step 4
```

Generate only rerouting graph CSVs:

```powershell
python .\run_all_pipeline.py --root . --from-step 5 --to-step 5
```

Enable full runner traceback for debugging:

```powershell
$env:PIPELINE_DEBUG="1"; python .\run_all_pipeline.py --root .
```
---
## Running the Preprocessing Scripts

Open PowerShell and move to the project directory:

```powershell
cd D:\porto\porto_preprocess
```

The scripts should normally be executed in numerical order.

### 1. Map-match the Porto taxi trajectories

```powershell
python .\1_porto_map_match.py --input .\data\porto_train.csv --output .\outputs\porto_train_edge_mapmatched.csv --diagnostics .\logs\map_matching_diagnostics.csv --graph .\data\porto_full_drive.graphml --workers 4 --merge-input-columns
```

This produces:

* `outputs\porto_train_edge_mapmatched.csv`
* `logs\map_matching_diagnostics.csv`

The output contains the realized edge, edge travel-time, and node sequences for each successfully matched trip.

### 2. Infer hourly background traffic

```powershell
python .\2_infer_background_from_edge_times.py --input .\outputs\porto_train_edge_mapmatched.csv --graph .\data\porto_full_drive.graphml --output .\outputs\background_congestion_flow.csv --free-flow-output .\outputs\sim_edge_lookup_freeflow.csv --workers 8 --warmup-days 5
```

This produces:

* `outputs\background_congestion_flow.csv`
* `outputs\sim_edge_lookup_freeflow.csv`

The background traffic is inferred by inverting the congestion equation using the observed edge travel times and GraphML free-flow travel times.

### 3. Build observed OD route shares

```powershell
python .\3_build_od_route_shares.py --input .\outputs\porto_train_edge_mapmatched.csv --output .\outputs\od_route_share.csv --graph .\data\porto_full_drive.graphml --min-route-trips 2
```

This produces:

* `outputs\od_route_share.csv`

Each distinct realized edge sequence is treated as an observed route. Routes observed fewer than two times are removed by default in this command.

To keep routes observed only once, use:

```powershell
python .\3_build_od_route_shares.py --input .\outputs\porto_train_edge_mapmatched.csv --output .\outputs\od_route_share.csv --graph .\data\porto_full_drive.graphml --min-route-trips 1
```

### 4. Generate candidate routes and waypoints

```powershell
python .\4_generate_candidate_paths.py --graph .\data\porto_full_drive.graphml --ods .\outputs\od_route_share.csv --routes-out .\outputs\candidate_routes.csv --waypoints-out .\outputs\route_waypoints.csv --workers 4
```

This produces:

* `outputs\candidate_routes.csv`
* `outputs\route_waypoints.csv`

The corrected version of this script writes node and edge sequences using semicolons between sequence elements:

```text
route_node_seq = node1;node2;node3
edge_seq = node1|node2|0;node2|node3|0
```

### 5. Convert the GraphML network to rerouting CSV files

```powershell
python .\5_graphml_to_reroute_csv.py .\data\porto_full_drive.graphml --length-output .\outputs\length_network.csv --time-output .\outputs\time_network.csv
```

This produces:

* `outputs\length_network.csv`
* `outputs\time_network.csv`

The length network uses edge distance as its routing weight, while the time network uses free-flow travel time.

## Complete execution order

```powershell
python .\1_porto_map_match.py --input .\data\porto_train.csv --output .\outputs\porto_train_edge_mapmatched.csv --diagnostics .\logs\map_matching_diagnostics.csv --graph .\data\porto_full_drive.graphml --workers 4 --merge-input-columns
```

```powershell
python .\2_infer_background_from_edge_times.py --input .\outputs\porto_train_edge_mapmatched.csv --graph .\data\porto_full_drive.graphml --output .\outputs\background_congestion_flow.csv --free-flow-output .\outputs\sim_edge_lookup_freeflow.csv --workers 8 --warmup-days 5
```

```powershell
python .\3_build_od_route_shares.py --input .\outputs\porto_train_edge_mapmatched.csv --output .\outputs\od_route_share.csv --graph .\data\porto_full_drive.graphml --min-route-trips 2
```

```powershell
python .\4_generate_candidate_paths.py --graph .\data\porto_full_drive.graphml --ods .\outputs\od_route_share.csv --routes-out .\outputs\candidate_routes.csv --waypoints-out .\outputs\route_waypoints.csv --workers 4
```

```powershell
python .\5_graphml_to_reroute_csv.py .\data\porto_full_drive.graphml --length-output .\outputs\length_network.csv --time-output .\outputs\time_network.csv
```

## Running the complete pipeline automatically

To run all stages through the pipeline runner and receive Telegram updates:

```powershell
$env:TELEGRAM_BOT_TOKEN="YOUR_BOT_TOKEN"; $env:TELEGRAM_CHAT_ID="YOUR_CHAT_ID"; python .\run_all_pipeline.py --root .
```

To skip stages whose output files already exist:

```powershell
python .\run_all_pipeline.py --root . --skip-existing
```

To execute only a particular range of stages:

```powershell
python .\run_all_pipeline.py --root . --from-step 2 --to-step 5
```

For example, to regenerate only candidate routes:

```powershell
python .\run_all_pipeline.py --root . --from-step 4 --to-step 4
```
---

# Reproducibility notes

- Keep the GraphML file fixed across all preprocessing stages and Java runs.
- Record the pipeline log associated with every generated dataset.
- Preserve the exact script versions used to generate an output set.
- Record worker counts and stage-specific arguments.
- Candidate generation uses a deterministic seed by default, but changes to graph ordering, dependency versions, or route-generation parameters can still affect output.
- Do not mix files from different GraphML versions or preprocessing runs unless their edge ID spaces have been verified as identical.

---

# License and data attribution

Copyright © 2026 Billy Suryono Hadisahputra. All rights reserved.
