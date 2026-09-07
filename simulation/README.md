# Porto Taxi Simulation and Parameter Sweep

A Java 17 research project for simulating persistent taxis, congestion-aware route choice, and dynamic rerouting. It compares simulated trips with observed routes and searches for parameter settings that reproduce route shares, edge flows, and travel times.

## How it works

1. Load candidate routes, observed stable routes, edge statistics, and rerouting graphs from CSV files.
2. Generate origin–destination (OD) requests immediately or from an hourly demand profile.
3. Dispatch taxis, reposition them between trips, and choose service routes.
4. Update congestion, travel-time memory, and optional edge-use habits. Rerouting can change the remaining path; completed paths can optionally become future candidates.
5. Match realized trips against observed routes, calculate metrics, and rank runs.

Each simulation runs independently. The runner executes parameter configurations concurrently using `nThreads`.

## Requirements and running

- JDK 17 or newer, with `java` and `javac` on your PATH.
- Maven if using the Maven commands. The project declares no external application dependencies; Maven may need to download build plugins.
- The CSV files referenced by your configuration.

Run commands from the simulation/. Relative paths in properties files resolve against the working directory, **not the configuration file's directory**. Use forward slashes for Windows paths inside `.properties` files.

### Maven

Compile:

```powershell
mvn -q compile
```

Compile and run the existing Porto configuration:

```powershell
mvn -q compile exec:java "-Dexec.args=--config=examples/quickstart.properties"
```

Review dataset paths, fleet size, sweep size, and output location before running. Omitting `--config` selects `examples/sweep.properties`.

### Java directly in Windows PowerShell

```powershell
New-Item -ItemType Directory -Force out | Out-Null
$javaSources = Get-ChildItem -Recurse -Filter *.java src/main/java
javac --release 17 -d out $javaSources.FullName
java -cp out porto.sweep.app.RunSweepMain --config=examples/sweep.properties
```

## Small-run configuration

Save this as `examples/quickstart.properties`, then run either command above with `--config=examples/quickstart.properties`. It uses existing Porto inputs with a small fleet and one parameter configuration. This is a setup check, not a calibrated experiment.

```properties
candidateRoutes=examples/candidate_routes.csv
routeWaypoints=examples/route_waypoints.csv
edgeLookup=examples/sim_edge_lookup_freeflow.csv
stableRoutes=examples/od_route_share.csv
rerouteTimeGraphCsv=examples/time_network.csv
rerouteLengthGraphCsv=examples/length_network.csv
backgroundTrafficCsv=examples/background_congestion_flow.csv

outputDir=examples/quickstart_runs/run_{timestamp}
seed=42
nTaxis=10
totalTrips=50
maxTripsPerOd=2
nThreads=1
topKOutputs=1

tripStartMode=immediate
sweepMode=grid
routeChoiceMode=behavioral
normalizeChoiceUtility=true

betaTimeGrid=1.0
betaMemoryGrid=0.1
betaEdgeHabitGrid=0.15
betaDetourGrid=0.15
betaComplexityGrid=0.05
decisionNoiseStdGrid=0.0

edgeHabitMode=collective
edgeHabitScale=10.0
memoryLearningRate=0.20

lookAheadEdges=4
rerouteTopK=5
rerouteMaxRelTime=2.0
rerouteMaxEdgeJaccard=0.95
slowdownTriggerRatioGrid=1.02
slowdownTriggerSecGrid=3.0
rerouteGainThresholdSecGrid=5.0
rerouteCooldownSecGrid=120.0
maxReroutesGrid=1

```

Unspecified settings use defaults in `SweepConfig`. `{timestamp}` expands to `yyyyMMdd_HHmmss`. Choose a fresh output directory for each experiment because output files can be replaced.

## Input data

Node and edge sequences use pipe-separated values such as `a|b|c`. Keep edge identifiers consistent across route files, edge statistics, and graphs.

| Property | Contents |
| --- | --- |
| `candidateRoutes` | Candidate paths: `od_id`, `route_id`, `route_node_seq`, `edge_seq`; optional attributes include `avg_time_sec`, `avg_distance_m`, `route_rank`, and `stable_share_norm`. |
| `stableRoutes` | Observed paths: `od_id`, `route_id`, `sample_edge_seq`, optionally `sample_route_node_seq`; include `stable_share_norm`, `od_trip_count`, and `avg_time_sec` for demand and evaluation. |
| `edgeLookup` | `edge_id`, `mean_traversal_time_sec`, `edge_traversal_count`, and optional hour information such as `hour_of_day_str`. |
| `rerouteTimeGraphCsv` | Directed graph rows: `from_node`, `to_node`, `edge_id`. |
| `rerouteLengthGraphCsv` | Directed graph rows with the same schema for alternative rerouting searches. |
| `routeWaypoints` | Required configuration entry, but coordinates are currently skipped by the loader to reduce memory usage. |
| `backgroundTrafficCsv` | Optional flow: `edge_id`, `background_flow`, and optional `hour_of_day`. A missing hour applies flow to every hour. |
| `taxiClassesCsv` | Optional persistent profiles with `class_id`, `share`, and behavioral overrides. See `examples/synthetic/taxi_classes.csv`. |

The loader supports additional aliases and fallback values; `DataRepository.java` defines exact behavior. Invalid or incomplete rows may be skipped, so inspect repository counts printed at startup.

## Configuration controls

| Area | Main properties |
| --- | --- |
| Fleet and demand | `seed`, `nTaxis`, `totalTrips`, `maxTripsPerOd`, `tripStartMode`, `simulationDays`, `hourlyDemandWeights` |
| Execution | `nThreads`, `outputDir`, `topKOutputs` |
| Choice and memory | `routeChoiceMode`, `normalizeChoiceUtility`, `memoryLearningRate`, `edgeHabitMode`, `edgeHabitScale` |
| Rerouting | `lookAheadEdges`, `rerouteTopK`, `rerouteMaxRelTime`, `rerouteMaxEdgeJaccard`, `debugRerouting` |
| Route learning | `learnObservedRoutesAsCandidates`, `learnedRouteMaxPerOd`, `learnedRouteMinEdges` |
| Repositioning | `repositionUsesBaseCost` |

`tripStartMode` accepts `immediate` or `hourly`. Hourly demand requires exactly 24 nonnegative `hourlyDemandWeights`, with at least one positive value. `edgeHabitMode` accepts `off`, `collective`, or `taxi`.

Supported route-choice modes are `behavioral`, `shortest`, `static_fastest`, and `dynamic_fastest`.

Sweep dimensions use comma-separated values:

- Behavior: `betaTimeGrid`, `betaMemoryGrid`, `betaEdgeHabitGrid`, `betaDetourGrid`, `betaComplexityGrid`, `decisionNoiseStdGrid`.
- Rerouting: `slowdownTriggerRatioGrid`, `slowdownTriggerSecGrid`, `rerouteGainThresholdSecGrid`, `rerouteCooldownSecGrid`, `maxReroutesGrid`.
- Traffic: `bprAlphaGrid`, `bprBetaGrid`, `capacityScaleGrid`, `backgroundTrafficScaleGrid`.

Set `maxReroutesGrid=0` to disable dynamic rerouting when no taxi-class override enables it. Taxi-class profiles can override behavioral settings while retaining the run's traffic-physics parameters.

### Experiment modes

| `sweepMode` | Behavior |
| --- | --- |
| `grid` (default) | Run the Cartesian product of parameter grids. Large grids multiply quickly. |
| `lhs` | Latin hypercube sampling using `lhsSamples` and `lhsSeed`. Multiple continuous-grid values define a minimum and maximum; one value fixes a dimension. `maxReroutesGrid` must contain exactly one value. |
| `benchmark` | Compare route-choice modes selected by `benchmarkModes`. |
| `replicate` | Read finalist parameters from `replicationCandidatesCsv` and repeat them for `replicationSeeds`. |

LHS uses the same simulation `seed` at every sampled point. Replication produces aggregate results across seeds. Consult the replication-job loading code in `RunSweepMain.java` for the finalist CSV schema and validation.

## Evaluation and ranking

The default objective combines four errors after dividing each by its configured scale:

```text
objectiveLoss = sum(weight * error / scale) / sum(weights)
```

Components are final route-share Jensen–Shannon divergence, edge-flow weighted mean absolute error, travel-time weighted mean absolute error in seconds, and novelty-rate error. Configure them with `objectiveWeight*` and `objectiveScale*` properties.

Lower loss is better. With `objectiveEnabled=true`, eligible runs rank first: eligibility requires finite loss and completion of at least `objectiveMinimumCompletionRate` (default `0.995`). Eligible runs rank by loss; ineligible runs by completion rate, then loss. Jaccard similarity breaks remaining metric ties. With `objectiveEnabled=false`, ranking uses completion rate followed by mean best Jaccard similarity.

Route classification shortlists observed routes by edge-set Jaccard, then scores normalized longest common subsequence and edge-bigram Jaccard. The `routeMatch*` settings control shortlisting, score weights, acceptance threshold, and winning margin. Trips without an accepted match enter the unmatched bucket; ambiguous classifications are also recorded diagnostically.

`objectiveObservedNoveltyRate=-1` infers novelty from residual stable-route share; a value from `0` to `1` supplies a fixed target. Interpret results alongside completion and reference-data coverage.

## Outputs

The output directory contains:

- A copy of the input configuration and `objective_settings.properties`.
- `sweep_results.csv`: metrics and parameters for every run.
- `best_trip_outcomes.csv`, `best_config.properties`, `best_summary.txt`, and `best_objective_settings.properties`.
- `top_XX_*` directories with outcomes, parameters, objective settings, and summaries. `topKOutputs=0` omits these directories; best-run files are still written.
- Simulation progress CSV files.

LHS and benchmark modes also write their design CSVs. Replication adds `replication_design.csv`, `replication_results.csv`, `replication_summary.csv`, and, when an aggregate winner is available, `best_mean_config.properties` and `best_mean_summary.txt`.

## Code layout

```text
src/main/java/porto/sweep/
  app/       Entry point, experiment design, ranking, output writing
  config/    Properties loading and taxi-class profiles
  io/        CSV parsing and network/reference-data loading
  sim/       Simulation, taxi state, parameters, objective metrics
  eval/      Jaccard and sequence-based route matching
  model/     Routes, edges, observed references, trip outcomes
  util/      Pipe-separated sequence helpers
examples/    Inputs, experiment configs, notebooks, and saved runs
testdata/    Legacy small dataset and sample outputs
```

Start with `RunSweepMain.java` for orchestration, `SweepConfig.java` for accepted properties and defaults, and `SimulationEngine.java` for model behavior.

## Development notes and limitations

This is a research prototype, not a full traffic microsimulator. Results depend on network coverage, reference quality, and experiment settings. Observed routes also influence demand generation, so evaluation should not automatically be interpreted as held-out validation.

Historical example configurations contain obsolete properties or comments. Settings such as `betaPriorGrid`, `betaHabitGrid`, and `habitMode` are not loaded by the current `SweepConfig`. The legacy `testdata/sweep.properties` uses Linux-specific paths and omits required rerouting graph properties; update it before use on Windows.

There is currently no `src/test` automated test suite. Compilation checks Java sources; use a small simulation and inspect its load summary, completion metrics, and outputs before launching a large experiment.
