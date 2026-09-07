package porto.sweep.app;

import porto.sweep.config.SweepConfig;
import porto.sweep.io.DataRepository;
import porto.sweep.io.SimpleCsv;
import porto.sweep.sim.SimulationEngine;
import porto.sweep.sim.SweepParameters;
import porto.sweep.sim.SweepRunResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RunSweepMain {
    private static final Comparator<IndexedResult> RANKING_ORDER = (a, b) -> {
        SweepRunResult ar = a.result;
        SweepRunResult br = b.result;

        if (ar.objectiveEnabled || br.objectiveEnabled) {
            int eligibleComparison = Boolean.compare(br.objectiveEligible, ar.objectiveEligible);
            if (eligibleComparison != 0) {
                return eligibleComparison;
            }
            if (ar.objectiveEligible && br.objectiveEligible) {
                int lossComparison = Double.compare(ar.objectiveLoss, br.objectiveLoss);
                if (lossComparison != 0) {
                    return lossComparison;
                }
            } else {
                int completionComparison = Double.compare(br.completionRate, ar.completionRate);
                if (completionComparison != 0) {
                    return completionComparison;
                }
                int lossComparison = Double.compare(ar.objectiveLoss, br.objectiveLoss);
                if (lossComparison != 0) {
                    return lossComparison;
                }
            }
        } else {
            int completionComparison = Double.compare(br.completionRate, ar.completionRate);
            if (completionComparison != 0) {
                return completionComparison;
            }
        }

        int jaccardComparison = Double.compare(br.meanBestJaccard, ar.meanBestJaccard);
        if (jaccardComparison != 0) {
            return jaccardComparison;
        }
        return Integer.compare(a.index, b.index);
    };

    public static void main(String[] args) throws Exception {
        Path configPath = resolveConfigPath(args);
        SweepConfig config = SweepConfig.load(configPath);
        Files.createDirectories(config.outputDir);
        // Copy the exact config used for this run into the output directory
        Files.copy(
                configPath,
                config.outputDir.resolve(configPath.getFileName()),
                StandardCopyOption.REPLACE_EXISTING
        );
        Files.writeString(
                config.outputDir.resolve("objective_settings.properties"),
                config.objectivePropertiesText(),
                StandardCharsets.UTF_8
        );

        System.out.println("Loading data...");
        DataRepository repo = DataRepository.load(
                config.candidateRoutes,
                config.routeWaypoints,
                config.edgeLookup,
                config.stableRoutes,
                config.rerouteTimeGraphCsv,
                config.rerouteLengthGraphCsv,
                config.backgroundTrafficCsv
        );
        System.out.println("Candidate ODs: " + repo.getCandidateRoutesByOd().size());
        System.out.println("Stable ODs: " + repo.getStableRoutesByOd().size());
        System.out.println("Edges with stats: " + repo.getEdgeStatsById().size());
        if (config.backgroundTrafficCsv != null) {
            System.out.println("Background traffic: " + config.backgroundTrafficCsv + " scale=" + config.backgroundTrafficScale);
        } else {
            System.out.println("Background traffic: disabled");
        }
        if (config.taxiClasses == null || config.taxiClasses.isEmpty()) {
            System.out.println("Taxi classes: disabled (homogeneous sweep parameters)");
        } else {
            System.out.println("Taxi classes: " + config.taxiClassesCsv);
            config.taxiClasses.forEach(profile ->
                    System.out.println("  class=" + profile.classId + " share=" + profile.share));
        }
        System.out.println(repo.loadSummary());
        System.out.println("Objective enabled: " + config.objectiveEnabled);
        System.out.println("Objective completion gate: " + config.objectiveMinimumCompletionRate);
        System.out.println("Objective weights: finalShare=" + config.objectiveWeightFinalRouteShare
                + " edgeFlow=" + config.objectiveWeightEdgeFlow
                + " travelTime=" + config.objectiveWeightTravelTime
                + " novelty=" + config.objectiveWeightNovelty);

        SweepDesignOptions sweepDesign = SweepDesignOptions.load(configPath);
        List<SweepJob> jobs = buildJobs(config, sweepDesign);
        System.out.println("Sweep mode: " + sweepDesign.mode);
        if (!sweepDesign.isBenchmark()) {
            System.out.println("Route choice mode: " + sweepDesign.routeChoiceMode);
        }
        if (sweepDesign.isLhs()) {
            System.out.println("LHS samples: " + sweepDesign.lhsSamples + " | LHS seed: " + sweepDesign.lhsSeed);
            writeSweepDesign(config.outputDir.resolve("lhs_design.csv"), jobs);
        } else if (sweepDesign.isBenchmark()) {
            System.out.println("Benchmark modes: " + String.join(",", sweepDesign.benchmarkModes));
            writeSweepDesign(config.outputDir.resolve("benchmark_design.csv"), jobs);
        } else if (sweepDesign.isReplicate()) {
            System.out.println("Replication finalist CSV: " + sweepDesign.replicationCandidatesCsv);
            System.out.println("Replication seeds: " + sweepDesign.replicationSeeds);
            writeSweepDesign(config.outputDir.resolve("replication_design.csv"), jobs);
        }
        System.out.println("Total sweep jobs: " + jobs.size());

        int nThreads = Math.max(1, config.nThreads);
        System.out.println("Running with threads: " + nThreads);

        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        CompletionService<IndexedResult> completion = new ExecutorCompletionService<>(pool);
        List<Path> temporaryOutcomeFiles = new ArrayList<>();
        List<IndexedResult> indexedResults = new ArrayList<>();
        List<IndexedResult> retainedDetailedResults = new ArrayList<>();
        int retainedLimit = Math.max(1, config.topKOutputs);

        try {
            for (int i = 0; i < jobs.size(); i++) {
                final int jobIndex = i;
                final SweepJob job = jobs.get(i);
                final Path tripOutcomesPath = Files.createTempFile(
                        config.outputDir,
                        ".trip_outcomes_",
                        ".csv"
                );
                temporaryOutcomeFiles.add(tripOutcomesPath);

                completion.submit(() -> {
                    SimulationEngine engine = new SimulationEngine(repo, config);
                    SweepRunResult result = engine.run(job.params, job.seed, tripOutcomesPath);
                    return new IndexedResult(jobIndex, result, tripOutcomesPath, job.seed, job.label);
                });
            }

            for (int done = 0; done < jobs.size(); done++) {
                IndexedResult ir = completion.take().get();
                indexedResults.add(ir);
                retainDetailedResult(ir, retainedDetailedResults, retainedLimit);
                System.out.println(
                        "Finished " + (done + 1) + "/" + jobs.size()
                                + " : " + ir.result.parameters.id()
                                + " | objective=" + ir.result.objectiveLoss
                                + " | eligible=" + ir.result.objectiveEligible
                                + " | finalShareJSD=" + ir.result.finalRouteShareJsd
                                + " | edgeFlowWMAE=" + ir.result.edgeFlowWmae
                                + " | travelTimeWMAE=" + ir.result.travelTimeWmaeSec
                                + " | noveltyError=" + ir.result.noveltyRateError
                                + " | completion=" + ir.result.completionRate
                                + " | runtimeSec=" + ir.result.runtimeSec
                );
            }

            indexedResults.sort(Comparator.comparingInt(r -> r.index));
            writeSweepResults(config.outputDir.resolve("sweep_results.csv"), indexedResults);
            if (sweepDesign.isReplicate()) {
                writeReplicationResults(config.outputDir.resolve("replication_results.csv"), indexedResults);
                writeReplicationSummary(config.outputDir, indexedResults, sweepDesign.replicationSeeds);
            }

            List<IndexedResult> rankedResults = new ArrayList<>(indexedResults);
            rankedResults.sort(RANKING_ORDER);

            if (rankedResults.isEmpty()) {
                throw new IllegalStateException("No sweep result was produced.");
            }

            writeTopKOutputs(config.outputDir, rankedResults, config.topKOutputs, config);

            IndexedResult best = rankedResults.get(0);
            writeBestOutputs(config.outputDir, best, config);

            System.out.println("Done.");
            System.out.println("Best objective eligible = " + best.result.objectiveEligible);
            System.out.println("Best objective loss = " + best.result.objectiveLoss);
            System.out.println("Best final route-share JSD = " + best.result.finalRouteShareJsd);
            System.out.println("Best edge-flow WMAE = " + best.result.edgeFlowWmae);
            System.out.println("Best travel-time WMAE sec = " + best.result.travelTimeWmaeSec);
            System.out.println("Best novelty-rate error = " + best.result.noveltyRateError);
            System.out.println("Best completion rate = " + best.result.completionRate);
            System.out.println("Diagnostic mean Jaccard = " + best.result.meanBestJaccard);
            System.out.println("Best config id = " + best.result.parameters.id());
            openFolder(String.valueOf(config.outputDir));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel sweep interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Parallel sweep failed", e.getCause());
        } finally {
            pool.shutdownNow();
            cleanupTemporaryOutcomeFiles(temporaryOutcomeFiles);
        }
    }

    private static List<SweepJob> buildJobs(SweepConfig config, SweepDesignOptions design) throws IOException {
        if (design.isBenchmark()) {
            return buildBenchmarkJobs(config, design);
        }
        if (design.isReplicate()) {
            return buildReplicationJobs(config, design);
        }
        if (design.isLhs()) {
            return buildLhsJobs(config, design);
        }
        return buildCartesianJobs(config, design);
    }

    /**
     * Classical deterministic route-choice benchmarks. Rerouting is always
     * disabled so the benchmark measures only the route selected at departure.
     *
     * shortest        -> minimum candidate avgDistanceM
     * static_fastest  -> minimum candidate avgTimeSec
     * dynamic_fastest -> minimum current traffic cost (live + background BPR),
     *                    excluding taxi-specific memory and decision noise
     */
    private static List<SweepJob> buildBenchmarkJobs(SweepConfig config, SweepDesignOptions design) {
        double ratio = firstValue(config.slowdownTriggerRatioGrid, 1.08);
        double slowSec = firstValue(config.slowdownTriggerSecGrid, 15.0);
        double gain = firstValue(config.rerouteGainThresholdSecGrid, 22.0);
        double cool = firstValue(config.rerouteCooldownSecGrid, 140.0);
        double bprAlpha = firstValue(config.bprAlphaGrid, 0.5);
        double bprBeta = firstValue(config.bprBetaGrid, 2.0);
        double capacityScale = firstValue(config.capacityScaleGrid, 1.0);
        double backgroundScale = firstValue(config.backgroundTrafficScaleGrid, config.backgroundTrafficScale);

        List<SweepJob> jobs = new ArrayList<>(design.benchmarkModes.size());
        for (String routeChoiceMode : design.benchmarkModes) {
            SweepParameters params = new SweepParameters(
                    0.0, 0.0, 0.0, 0.0, 0.0,
                    0.0, ratio, slowSec, gain, cool, 0,
                    bprAlpha, bprBeta, capacityScale, backgroundScale, routeChoiceMode
            );
            jobs.add(new SweepJob(params, config.seed));
        }
        return jobs;
    }

    /**
     * Replicates an explicit paired finalist design across several simulation seeds.
     * The finalist CSV contains one paired parameter vector per row; values are
     * never Cartesian-mixed. Behavioral parameters are required. Rerouting controls
     * may optionally be supplied per row; otherwise they remain fixed from properties.
     */
    private static List<SweepJob> buildReplicationJobs(SweepConfig config, SweepDesignOptions design) throws IOException {
        requireSingleDiscrete(config.maxReroutesGrid, "maxReroutesGrid");

        double ratio = firstValue(config.slowdownTriggerRatioGrid, 1.08);
        double slowSec = firstValue(config.slowdownTriggerSecGrid, 15.0);
        double gain = firstValue(config.rerouteGainThresholdSecGrid, 22.0);
        double cool = firstValue(config.rerouteCooldownSecGrid, 140.0);
        int maxReroutes = config.maxReroutesGrid.get(0);
        double bprAlpha = firstValue(config.bprAlphaGrid, 0.5);
        double bprBeta = firstValue(config.bprBetaGrid, 2.0);
        double capacityScale = firstValue(config.capacityScaleGrid, 1.0);
        double backgroundScale = firstValue(config.backgroundTrafficScaleGrid, config.backgroundTrafficScale);

        List<SweepJob> jobs = new ArrayList<>();
        java.util.Set<String> labels = new java.util.HashSet<>();
        int[] rowIndex = {0};

        SimpleCsv.forEach(design.replicationCandidatesCsv, row -> {
            rowIndex[0]++;
            String label = firstNonBlank(row, "label", "finalist", "name", "id");
            if (label.isBlank()) {
                label = String.format(Locale.US, "finalist_%02d", rowIndex[0]);
            }
            if (!labels.add(label)) {
                throw new IllegalArgumentException("Duplicate replication finalist label: " + label);
            }

            double betaTime = requiredDouble(row, "betaTime", label);
            double betaMemory = requiredDouble(row, "betaMemory", label);
            double betaEdgeHabit = requiredDouble(row, "betaEdgeHabit", label);
            double betaDetour = requiredDouble(row, "betaDetour", label);
            double betaComplexity = requiredDouble(row, "betaComplexity", label);
            double noise = requiredDouble(row, "decisionNoiseStd", label);

            // Optional per-finalist reroute overrides. If omitted, replicate mode
            // keeps the corresponding value fixed from the properties file.
            double finalistRatio = optionalDouble(row, "slowdownTriggerRatio", ratio, label);
            double finalistSlowSec = optionalDouble(row, "slowdownTriggerSec", slowSec, label);
            double finalistGain = optionalDouble(row, "rerouteGainThresholdSec", gain, label);
            double finalistCool = optionalDouble(row, "rerouteCooldownSec", cool, label);
            int finalistMaxReroutes = optionalNonNegativeInt(row, "maxReroutes", maxReroutes, label);

            // Optional per-finalist route-choice mode. This lets a single paired
            // validation experiment compare behavioral models with deterministic
            // baselines such as dynamic_fastest while keeping seeds/network physics aligned.
            String finalistRouteChoiceMode = firstNonBlank(row, "routeChoiceMode", "route_choice_mode");
            if (finalistRouteChoiceMode.isBlank()) {
                finalistRouteChoiceMode = design.routeChoiceMode;
            } else {
                finalistRouteChoiceMode = SweepDesignOptions.normalizeRouteChoiceMode(finalistRouteChoiceMode);
            }

            SweepParameters params = new SweepParameters(
                    betaTime, betaMemory, betaEdgeHabit, betaDetour, betaComplexity,
                    noise, finalistRatio, finalistSlowSec, finalistGain, finalistCool, finalistMaxReroutes,
                    bprAlpha, bprBeta, capacityScale, backgroundScale,
                    finalistRouteChoiceMode
            );

            for (long seed : design.replicationSeeds) {
                jobs.add(new SweepJob(params, seed, label));
            }
        });

        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("No finalist rows found in replicationCandidatesCsv: "
                    + design.replicationCandidatesCsv);
        }
        return jobs;
    }

    private static String firstNonBlank(java.util.Map<String, String> row, String... keys) {
        for (String key : keys) {
            String value = row.get(key);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static double requiredDouble(java.util.Map<String, String> row, String key, String label) {
        String raw = firstNonBlank(row, key);
        if (raw.isBlank()) {
            throw new IllegalArgumentException("Missing " + key + " for replication finalist " + label);
        }
        double value = Double.parseDouble(raw);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(key + " must be finite for replication finalist " + label);
        }
        return value;
    }

    private static double optionalDouble(java.util.Map<String, String> row, String key, double fallback, String label) {
        String raw = firstNonBlank(row, key);
        if (raw.isBlank()) {
            return fallback;
        }
        double value = Double.parseDouble(raw);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(key + " must be finite for replication finalist " + label);
        }
        return value;
    }

    private static int optionalNonNegativeInt(java.util.Map<String, String> row, String key, int fallback, String label) {
        String raw = firstNonBlank(row, key);
        if (raw.isBlank()) {
            return fallback;
        }
        int value = Integer.parseInt(raw);
        if (value < 0) {
            throw new IllegalArgumentException(key + " must be non-negative for replication finalist " + label);
        }
        return value;
    }

    private static double firstValue(List<Double> values, double fallback) {
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }

    private static List<SweepJob> buildCartesianJobs(SweepConfig config, SweepDesignOptions design) {
        List<SweepJob> jobs = new ArrayList<>();

        for (double betaTime : config.betaTimeGrid) {
            for (double betaMemory : config.betaMemoryGrid) {
                for (double betaEdgeHabit : config.betaEdgeHabitGrid) {
                    for (double betaDetour : config.betaDetourGrid) {
                        for (double betaComplexity : config.betaComplexityGrid) {
                            for (double noise : config.decisionNoiseStdGrid) {
                                for (double ratio : config.slowdownTriggerRatioGrid) {
                                    for (double slowSec : config.slowdownTriggerSecGrid) {
                                        for (double gain : config.rerouteGainThresholdSecGrid) {
                                            for (double cool : config.rerouteCooldownSecGrid) {
                                                for (int maxReroutes : config.maxReroutesGrid) {
                                                    for (double bprAlpha : config.bprAlphaGrid) {
                                                        for (double bprBeta : config.bprBetaGrid) {
                                                            for (double capacityScale : config.capacityScaleGrid) {
                                                                for (double backgroundScale : config.backgroundTrafficScaleGrid) {
                                                                    SweepParameters params = new SweepParameters(
                                                                            betaTime, betaMemory,
                                                                            betaEdgeHabit, betaDetour, betaComplexity,
                                                                            noise, ratio, slowSec, gain, cool, maxReroutes,
                                                                            bprAlpha, bprBeta, capacityScale, backgroundScale,
                                                                            design.routeChoiceMode
                                                                    );
                                                                    jobs.add(new SweepJob(params, config.seed));
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return jobs;
    }

    /**
     * Generates a randomized Latin-hypercube design directly from the existing
     * *Grid properties. In LHS mode:
     *   - one value means the parameter is fixed;
     *   - two or more values mean [min(grid), max(grid)] is the sampling range.
     *
     * maxReroutes is discrete and must contain exactly one value. Run a separate
     * LHS experiment for each discrete maxReroutes setting instead of mixing it
     * into a continuous LHS.
     */
    private static List<SweepJob> buildLhsJobs(SweepConfig config, SweepDesignOptions design) {
        int n = design.lhsSamples;
        requireSingleDiscrete(config.maxReroutesGrid, "maxReroutesGrid");

        Random rng = new Random(design.lhsSeed);
        double[] betaTime = lhsDimension(config.betaTimeGrid, n, rng, "betaTimeGrid");
        double[] betaMemory = lhsDimension(config.betaMemoryGrid, n, rng, "betaMemoryGrid");
        double[] betaEdgeHabit = lhsDimension(config.betaEdgeHabitGrid, n, rng, "betaEdgeHabitGrid");
        double[] betaDetour = lhsDimension(config.betaDetourGrid, n, rng, "betaDetourGrid");
        double[] betaComplexity = lhsDimension(config.betaComplexityGrid, n, rng, "betaComplexityGrid");
        double[] noise = lhsDimension(config.decisionNoiseStdGrid, n, rng, "decisionNoiseStdGrid");
        double[] ratio = lhsDimension(config.slowdownTriggerRatioGrid, n, rng, "slowdownTriggerRatioGrid");
        double[] slowSec = lhsDimension(config.slowdownTriggerSecGrid, n, rng, "slowdownTriggerSecGrid");
        double[] gain = lhsDimension(config.rerouteGainThresholdSecGrid, n, rng, "rerouteGainThresholdSecGrid");
        double[] cool = lhsDimension(config.rerouteCooldownSecGrid, n, rng, "rerouteCooldownSecGrid");
        double[] bprAlpha = lhsDimension(config.bprAlphaGrid, n, rng, "bprAlphaGrid");
        double[] bprBeta = lhsDimension(config.bprBetaGrid, n, rng, "bprBetaGrid");
        double[] capacityScale = lhsDimension(config.capacityScaleGrid, n, rng, "capacityScaleGrid");
        double[] backgroundScale = lhsDimension(config.backgroundTrafficScaleGrid, n, rng, "backgroundTrafficScaleGrid");
        int maxReroutes = config.maxReroutesGrid.get(0);

        List<SweepJob> jobs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            SweepParameters params = new SweepParameters(
                    betaTime[i], betaMemory[i],
                    betaEdgeHabit[i], betaDetour[i], betaComplexity[i],
                    noise[i], ratio[i], slowSec[i], gain[i], cool[i], maxReroutes,
                    bprAlpha[i], bprBeta[i], capacityScale[i], backgroundScale[i],
                    design.routeChoiceMode
            );
            // Use the same simulation seed at every LHS point. This makes seed a
            // controlled blocking variable rather than another sampled parameter.
            jobs.add(new SweepJob(params, config.seed));
        }
        return jobs;
    }

    private static double[] lhsDimension(List<Double> values, int n, Random rng, String name) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(name + " must contain at least one value.");
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " contains a non-finite value: " + value);
            }
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        double[] out = new double[n];
        if (values.size() == 1 || min == max) {
            java.util.Arrays.fill(out, min);
            return out;
        }

        int[] strata = new int[n];
        for (int i = 0; i < n; i++) {
            strata[i] = i;
        }
        for (int i = n - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = strata[i];
            strata[i] = strata[j];
            strata[j] = tmp;
        }

        double width = max - min;
        for (int row = 0; row < n; row++) {
            double u = (strata[row] + rng.nextDouble()) / n;
            out[row] = min + u * width;
        }
        return out;
    }

    private static void requireSingleDiscrete(List<Integer> values, String name) {
        if (values == null || values.size() != 1) {
            throw new IllegalArgumentException(
                    "In sweepMode=lhs, " + name + " must contain exactly one discrete value. "
                            + "Run separate LHS experiments for different discrete settings."
            );
        }
    }

    private static void writeSweepDesign(Path path, List<SweepJob> jobs) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        )) {
            writer.write("designIndex,designLabel,simulationSeed,routeChoiceMode,betaTime,betaMemory,betaEdgeHabit,betaDetour,betaComplexity,"
                    + "decisionNoiseStd,slowdownTriggerRatio,slowdownTriggerSec,rerouteGainThresholdSec,"
                    + "rerouteCooldownSec,maxReroutes,bprAlpha,bprBeta,capacityScale,backgroundTrafficScale");
            writer.newLine();
            for (int i = 0; i < jobs.size(); i++) {
                SweepJob job = jobs.get(i);
                SweepParameters p = job.params;
                writer.write(String.format(
                        Locale.US,
                        "%d,%s,%d,%s,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%d,%.12g,%.12g,%.12g,%.12g",
                        i, csvField(job.label), job.seed, p.routeChoiceMode, p.betaTime, p.betaMemory, p.betaEdgeHabit, p.betaDetour,
                        p.betaComplexity, p.decisionNoiseStd, p.slowdownTriggerRatio,
                        p.slowdownTriggerSec, p.rerouteGainThresholdSec, p.rerouteCooldownSec,
                        p.maxReroutes, p.bprAlpha, p.bprBeta, p.capacityScale, p.backgroundTrafficScale
                ));
                writer.newLine();
            }
        }
    }

    private static void writeReplicationResults(Path path, List<IndexedResult> results) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        )) {
            writer.write("designIndex,finalistLabel,simulationSeed," + SweepRunResult.csvHeader());
            writer.newLine();
            for (IndexedResult ir : results) {
                writer.write(ir.index + "," + csvField(ir.label) + "," + ir.seed + "," + ir.result.toCsvRow());
                writer.newLine();
            }
        }
    }

    private static void writeReplicationSummary(Path outputDir,
                                                List<IndexedResult> results,
                                                List<Long> replicationSeeds) throws IOException {
        java.util.Map<String, List<IndexedResult>> groups = new java.util.LinkedHashMap<>();
        for (IndexedResult ir : results) {
            groups.computeIfAbsent(ir.label, k -> new ArrayList<>()).add(ir);
        }

        List<ReplicationAggregate> aggregates = new ArrayList<>();
        for (java.util.Map.Entry<String, List<IndexedResult>> entry : groups.entrySet()) {
            aggregates.add(ReplicationAggregate.of(entry.getKey(), entry.getValue()));
        }
        aggregates.sort(Comparator.comparingDouble(a -> a.meanObjectiveLoss));

        Path csvPath = outputDir.resolve("replication_summary.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(
                csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        )) {
            writer.write("rank,finalistLabel,nSeeds,meanObjectiveLoss,sdObjectiveLoss,meanEdgeFlowWmae,sdEdgeFlowWmae,"
                    + "meanTravelTimeWmaeSec,sdTravelTimeWmaeSec,meanBestJaccard,sdBestJaccard,"
                    + "meanActualSec,sdActualSec,meanCompletionRate,sdCompletionRate");
            writer.newLine();
            for (int i = 0; i < aggregates.size(); i++) {
                ReplicationAggregate a = aggregates.get(i);
                writer.write(String.format(Locale.US,
                        "%d,%s,%d,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g",
                        i + 1, csvField(a.label), a.nSeeds,
                        a.meanObjectiveLoss, a.sdObjectiveLoss,
                        a.meanEdgeFlowWmae, a.sdEdgeFlowWmae,
                        a.meanTravelTimeWmaeSec, a.sdTravelTimeWmaeSec,
                        a.meanBestJaccard, a.sdBestJaccard,
                        a.meanActualSec, a.sdActualSec,
                        a.meanCompletionRate, a.sdCompletionRate));
                writer.newLine();
            }
        }

        if (!aggregates.isEmpty()) {
            ReplicationAggregate best = aggregates.get(0);
            Files.writeString(
                    outputDir.resolve("best_mean_config.properties"),
                    best.parameters.toPropertiesText(),
                    StandardCharsets.UTF_8
            );
            String summary = "finalistLabel=" + best.label + System.lineSeparator()
                    + "replicationSeeds=" + replicationSeeds + System.lineSeparator()
                    + "nSeeds=" + best.nSeeds + System.lineSeparator()
                    + "meanObjectiveLoss=" + best.meanObjectiveLoss + System.lineSeparator()
                    + "sdObjectiveLoss=" + best.sdObjectiveLoss + System.lineSeparator()
                    + "meanEdgeFlowWmae=" + best.meanEdgeFlowWmae + System.lineSeparator()
                    + "sdEdgeFlowWmae=" + best.sdEdgeFlowWmae + System.lineSeparator()
                    + "meanTravelTimeWmaeSec=" + best.meanTravelTimeWmaeSec + System.lineSeparator()
                    + "sdTravelTimeWmaeSec=" + best.sdTravelTimeWmaeSec + System.lineSeparator()
                    + "meanBestJaccard=" + best.meanBestJaccard + System.lineSeparator()
                    + "sdBestJaccard=" + best.sdBestJaccard + System.lineSeparator()
                    + "meanActualSec=" + best.meanActualSec + System.lineSeparator()
                    + "sdActualSec=" + best.sdActualSec + System.lineSeparator();
            Files.writeString(outputDir.resolve("best_mean_summary.txt"), summary, StandardCharsets.UTF_8);

            System.out.println("Best replicated finalist by mean objective = " + best.label);
            System.out.println("Mean objective = " + best.meanObjectiveLoss + " | SD = " + best.sdObjectiveLoss);
            System.out.println("Mean edge-flow WMAE = " + best.meanEdgeFlowWmae);
            System.out.println("Mean travel-time WMAE sec = " + best.meanTravelTimeWmaeSec);
        }
    }

    private static String csvField(String value) {
        String v = value == null ? "" : value;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static double meanMetric(List<IndexedResult> rows,
                                     java.util.function.ToDoubleFunction<SweepRunResult> getter) {
        double sum = 0.0;
        for (IndexedResult row : rows) {
            sum += getter.applyAsDouble(row.result);
        }
        return rows.isEmpty() ? Double.NaN : sum / rows.size();
    }

    private static double sampleSdMetric(List<IndexedResult> rows,
                                         java.util.function.ToDoubleFunction<SweepRunResult> getter,
                                         double mean) {
        if (rows.size() <= 1) {
            return 0.0;
        }
        double sumSq = 0.0;
        for (IndexedResult row : rows) {
            double d = getter.applyAsDouble(row.result) - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / (rows.size() - 1));
    }

    private static final class ReplicationAggregate {
        final String label;
        final SweepParameters parameters;
        final int nSeeds;
        final double meanObjectiveLoss, sdObjectiveLoss;
        final double meanEdgeFlowWmae, sdEdgeFlowWmae;
        final double meanTravelTimeWmaeSec, sdTravelTimeWmaeSec;
        final double meanBestJaccard, sdBestJaccard;
        final double meanActualSec, sdActualSec;
        final double meanCompletionRate, sdCompletionRate;

        private ReplicationAggregate(String label, SweepParameters parameters, int nSeeds,
                                     double meanObjectiveLoss, double sdObjectiveLoss,
                                     double meanEdgeFlowWmae, double sdEdgeFlowWmae,
                                     double meanTravelTimeWmaeSec, double sdTravelTimeWmaeSec,
                                     double meanBestJaccard, double sdBestJaccard,
                                     double meanActualSec, double sdActualSec,
                                     double meanCompletionRate, double sdCompletionRate) {
            this.label = label;
            this.parameters = parameters;
            this.nSeeds = nSeeds;
            this.meanObjectiveLoss = meanObjectiveLoss;
            this.sdObjectiveLoss = sdObjectiveLoss;
            this.meanEdgeFlowWmae = meanEdgeFlowWmae;
            this.sdEdgeFlowWmae = sdEdgeFlowWmae;
            this.meanTravelTimeWmaeSec = meanTravelTimeWmaeSec;
            this.sdTravelTimeWmaeSec = sdTravelTimeWmaeSec;
            this.meanBestJaccard = meanBestJaccard;
            this.sdBestJaccard = sdBestJaccard;
            this.meanActualSec = meanActualSec;
            this.sdActualSec = sdActualSec;
            this.meanCompletionRate = meanCompletionRate;
            this.sdCompletionRate = sdCompletionRate;
        }

        static ReplicationAggregate of(String label, List<IndexedResult> rows) {
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("Empty replication group: " + label);
            }
            double mo = meanMetric(rows, r -> r.objectiveLoss);
            double me = meanMetric(rows, r -> r.edgeFlowWmae);
            double mt = meanMetric(rows, r -> r.travelTimeWmaeSec);
            double mj = meanMetric(rows, r -> r.meanBestJaccard);
            double ma = meanMetric(rows, r -> r.meanActualSec);
            double mc = meanMetric(rows, r -> r.completionRate);
            return new ReplicationAggregate(
                    label, rows.get(0).result.parameters, rows.size(),
                    mo, sampleSdMetric(rows, r -> r.objectiveLoss, mo),
                    me, sampleSdMetric(rows, r -> r.edgeFlowWmae, me),
                    mt, sampleSdMetric(rows, r -> r.travelTimeWmaeSec, mt),
                    mj, sampleSdMetric(rows, r -> r.meanBestJaccard, mj),
                    ma, sampleSdMetric(rows, r -> r.meanActualSec, ma),
                    mc, sampleSdMetric(rows, r -> r.completionRate, mc)
            );
        }
    }

    private static void retainDetailedResult(IndexedResult candidate,
                                             List<IndexedResult> retained,
                                             int retainedLimit) throws IOException {
        retained.add(candidate);
        retained.sort(RANKING_ORDER);
        while (retained.size() > retainedLimit) {
            IndexedResult removed = retained.remove(retained.size() - 1);
            Files.deleteIfExists(removed.tripOutcomesPath);
        }
    }

    private static void writeSweepResults(Path path, List<IndexedResult> indexedResults) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            writer.write(SweepRunResult.csvHeader());
            writer.newLine();

            for (IndexedResult ir : indexedResults) {
                writer.write(ir.result.toCsvRow());
                writer.newLine();
            }
        }
    }

    private static void writeBestOutputs(Path outputDir, IndexedResult best, SweepConfig config) throws IOException {
        requireOutcomeFile(best);
        Files.copy(
                best.tripOutcomesPath,
                outputDir.resolve("best_trip_outcomes.csv"),
                StandardCopyOption.REPLACE_EXISTING
        );
        Files.writeString(
                outputDir.resolve("best_config.properties"),
                best.result.parameters.toPropertiesText(),
                StandardCharsets.UTF_8
        );
        Files.writeString(
                outputDir.resolve("best_summary.txt"),
                summaryText(best.result),
                StandardCharsets.UTF_8
        );
        Files.writeString(
                outputDir.resolve("best_objective_settings.properties"),
                config.objectivePropertiesText(),
                StandardCharsets.UTF_8
        );
    }

    private static String summaryText(SweepRunResult result) {
        StringBuilder summary = new StringBuilder();
        summary.append("objectiveEnabled=").append(result.objectiveEnabled).append(System.lineSeparator());
        summary.append("objectiveEligible=").append(result.objectiveEligible).append(System.lineSeparator());
        summary.append("objectiveLoss=").append(result.objectiveLoss).append(System.lineSeparator());
        summary.append("finalRouteShareJsd=").append(result.finalRouteShareJsd).append(System.lineSeparator());
        summary.append("finalRouteShareMae=").append(result.finalRouteShareMae).append(System.lineSeparator());
        summary.append("edgeFlowWmae=").append(result.edgeFlowWmae).append(System.lineSeparator());
        summary.append("travelTimeWmaeSec=").append(result.travelTimeWmaeSec).append(System.lineSeparator());
        summary.append("simulatedNoveltyRate=").append(result.simulatedNoveltyRate).append(System.lineSeparator());
        summary.append("observedNoveltyRate=").append(result.observedNoveltyRate).append(System.lineSeparator());
        summary.append("noveltyRateError=").append(result.noveltyRateError).append(System.lineSeparator());
        summary.append("normalizedFinalRouteShareError=").append(result.normalizedFinalRouteShareError).append(System.lineSeparator());
        summary.append("normalizedEdgeFlowError=").append(result.normalizedEdgeFlowError).append(System.lineSeparator());
        summary.append("normalizedTravelTimeError=").append(result.normalizedTravelTimeError).append(System.lineSeparator());
        summary.append("normalizedNoveltyError=").append(result.normalizedNoveltyError).append(System.lineSeparator());
        summary.append("completionRate=").append(result.completionRate).append(System.lineSeparator());
        summary.append("matchedTripCount=").append(result.matchedTripCount).append(System.lineSeparator());
        summary.append("unmatchedTripCount=").append(result.unmatchedTripCount).append(System.lineSeparator());
        summary.append("ambiguousTripCount=").append(result.ambiguousTripCount).append(System.lineSeparator());
        summary.append("noReferenceTripCount=").append(result.noReferenceTripCount).append(System.lineSeparator());
        summary.append("travelTimeComparableTripCount=").append(result.travelTimeComparableTripCount).append(System.lineSeparator());
        summary.append("meanRouteMatchScore=").append(result.meanRouteMatchScore).append(System.lineSeparator());
        summary.append("meanBestJaccard=").append(result.meanBestJaccard).append(System.lineSeparator());
        summary.append("meanActualSec=").append(result.meanActualSec).append(System.lineSeparator());
        summary.append("meanReroutes=").append(result.meanReroutes).append(System.lineSeparator());
        summary.append("dominantRouteMatchRate=").append(result.dominantRouteMatchRate).append(System.lineSeparator());
        summary.append("runtimeSec=").append(result.runtimeSec).append(System.lineSeparator());
        summary.append("runtimeMin=").append(result.runtimeSec / 60.0).append(System.lineSeparator());
        summary.append("tripCount=").append(result.tripCount).append(System.lineSeparator());
        return summary.toString();
    }

    private static Path resolveConfigPath(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                return Path.of(arg.substring("--config=".length()));
            }
        }
        return Path.of("examples/sweep.properties");
    }

    private static final class SweepDesignOptions {
        final String mode;
        final int lhsSamples;
        final long lhsSeed;
        final List<String> benchmarkModes;
        final String routeChoiceMode;
        final Path replicationCandidatesCsv;
        final List<Long> replicationSeeds;

        private SweepDesignOptions(String mode, int lhsSamples, long lhsSeed,
                                   List<String> benchmarkModes, String routeChoiceMode,
                                   Path replicationCandidatesCsv, List<Long> replicationSeeds) {
            this.mode = mode;
            this.lhsSamples = lhsSamples;
            this.lhsSeed = lhsSeed;
            this.benchmarkModes = benchmarkModes;
            this.routeChoiceMode = routeChoiceMode;
            this.replicationCandidatesCsv = replicationCandidatesCsv;
            this.replicationSeeds = replicationSeeds;
        }

        static SweepDesignOptions load(Path configPath) throws IOException {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(in);
            }

            String mode = props.getProperty("sweepMode", "grid").trim().toLowerCase(Locale.ROOT);
            if (!mode.equals("grid") && !mode.equals("lhs") && !mode.equals("benchmark") && !mode.equals("replicate")) {
                throw new IllegalArgumentException("sweepMode must be grid, lhs, benchmark, or replicate, got: " + mode);
            }

            int lhsSamples = Integer.parseInt(props.getProperty("lhsSamples", "32").trim());
            if (lhsSamples <= 0) {
                throw new IllegalArgumentException("lhsSamples must be positive.");
            }
            long lhsSeed = Long.parseLong(props.getProperty("lhsSeed", "20260825").trim());
            List<String> benchmarkModes = parseBenchmarkModes(props.getProperty(
                    "benchmarkModes",
                    "shortest,static_fastest,dynamic_fastest"
            ));
            String routeChoiceMode = normalizeRouteChoiceMode(props.getProperty("routeChoiceMode", "behavioral"));
            String replicationCsvRaw = props.getProperty("replicationCandidatesCsv", "").trim();
            Path replicationCandidatesCsv = replicationCsvRaw.isEmpty() ? null : Path.of(replicationCsvRaw);
            List<Long> replicationSeeds = parseLongList(props.getProperty("replicationSeeds", "42,20260827,20260828,20260829,20260830"));
            if (mode.equals("replicate")) {
                if (replicationCandidatesCsv == null) {
                    throw new IllegalArgumentException("replicationCandidatesCsv is required for sweepMode=replicate.");
                }
                if (replicationSeeds.isEmpty()) {
                    throw new IllegalArgumentException("replicationSeeds must contain at least one seed.");
                }
            }
            return new SweepDesignOptions(mode, lhsSamples, lhsSeed, benchmarkModes, routeChoiceMode,
                    replicationCandidatesCsv, replicationSeeds);
        }

        private static String normalizeRouteChoiceMode(String raw) {
            String mode = raw == null ? SweepParameters.CHOICE_BEHAVIORAL
                    : raw.trim().toLowerCase(Locale.ROOT);
            if (mode.equals("fastest")) {
                mode = SweepParameters.CHOICE_STATIC_FASTEST;
            } else if (mode.equals("dynamic")) {
                mode = SweepParameters.CHOICE_DYNAMIC_FASTEST;
            }
            if (!mode.equals(SweepParameters.CHOICE_BEHAVIORAL)
                    && !mode.equals(SweepParameters.CHOICE_SHORTEST)
                    && !mode.equals(SweepParameters.CHOICE_STATIC_FASTEST)
                    && !mode.equals(SweepParameters.CHOICE_DYNAMIC_FASTEST)) {
                throw new IllegalArgumentException(
                        "Unknown routeChoiceMode '" + raw
                                + "'. Use behavioral, shortest, static_fastest, or dynamic_fastest."
                );
            }
            return mode;
        }

        private static List<String> parseBenchmarkModes(String raw) {
            List<String> out = new ArrayList<>();
            for (String token : raw.split(",")) {
                String mode = token.trim().toLowerCase(Locale.ROOT);
                if (mode.isEmpty()) {
                    continue;
                }
                if (mode.equals("fastest")) {
                    mode = SweepParameters.CHOICE_STATIC_FASTEST;
                } else if (mode.equals("dynamic")) {
                    mode = SweepParameters.CHOICE_DYNAMIC_FASTEST;
                }
                if (!mode.equals(SweepParameters.CHOICE_SHORTEST)
                        && !mode.equals(SweepParameters.CHOICE_STATIC_FASTEST)
                        && !mode.equals(SweepParameters.CHOICE_DYNAMIC_FASTEST)) {
                    throw new IllegalArgumentException(
                            "Unknown benchmark mode '" + token + "'. Use shortest, static_fastest, or dynamic_fastest."
                    );
                }
                if (!out.contains(mode)) {
                    out.add(mode);
                }
            }
            if (out.isEmpty()) {
                throw new IllegalArgumentException("benchmarkModes must contain at least one benchmark.");
            }
            return List.copyOf(out);
        }

        private static List<Long> parseLongList(String raw) {
            List<Long> out = new ArrayList<>();
            for (String token : raw.split(",")) {
                String value = token.trim();
                if (!value.isEmpty()) {
                    long seed = Long.parseLong(value);
                    if (!out.contains(seed)) {
                        out.add(seed);
                    }
                }
            }
            return List.copyOf(out);
        }

        boolean isReplicate() {
            return mode.equals("replicate");
        }

        boolean isLhs() {
            return mode.equals("lhs");
        }

        boolean isBenchmark() {
            return mode.equals("benchmark");
        }
    }

    private static class SweepJob {
        final SweepParameters params;
        final long seed;
        final String label;

        SweepJob(SweepParameters params, long seed) {
            this(params, seed, "");
        }

        SweepJob(SweepParameters params, long seed, String label) {
            this.params = params;
            this.seed = seed;
            this.label = label == null ? "" : label;
        }
    }

    private static class IndexedResult {
        final int index;
        final SweepRunResult result;
        final Path tripOutcomesPath;
        final long seed;
        final String label;

        IndexedResult(int index, SweepRunResult result, Path tripOutcomesPath, long seed, String label) {
            this.index = index;
            this.result = result;
            this.tripOutcomesPath = tripOutcomesPath;
            this.seed = seed;
            this.label = label == null ? "" : label;
        }
    }

    private static void writeTopKOutputs(Path outputDir, List<IndexedResult> rankedResults, int topK, SweepConfig config) throws IOException {
        int limit = Math.min(Math.max(0, topK), rankedResults.size());

        for (int i = 0; i < limit; i++) {
            IndexedResult ir = rankedResults.get(i);
            SweepRunResult result = ir.result;
            requireOutcomeFile(ir);

            String rank = String.format("%02d", i + 1);
            String safeId = result.parameters.id().replaceAll("[^a-zA-Z0-9._-]", "_");
            Path dir = outputDir.resolve("top_" + rank + "_" + safeId);
            Files.createDirectories(dir);

            Files.copy(
                    ir.tripOutcomesPath,
                    dir.resolve("trip_outcomes.csv"),
                    StandardCopyOption.REPLACE_EXISTING
            );
            Files.writeString(
                    dir.resolve("config.properties"),
                    result.parameters.toPropertiesText(),
                    StandardCharsets.UTF_8
            );
            Files.writeString(
                    dir.resolve("objective_settings.properties"),
                    config.objectivePropertiesText(),
                    StandardCharsets.UTF_8
            );

            StringBuilder summary = new StringBuilder();
            summary.append("rank=").append(i + 1).append(System.lineSeparator());
            summary.append(summaryText(result));
            Files.writeString(dir.resolve("summary.txt"), summary.toString(), StandardCharsets.UTF_8);
        }
    }

    private static void requireOutcomeFile(IndexedResult result) throws IOException {
        if (!Files.isRegularFile(result.tripOutcomesPath)) {
            throw new IOException("Detailed trip output was not retained for ranked result: "
                    + result.result.parameters.id());
        }
    }

    private static void cleanupTemporaryOutcomeFiles(List<Path> paths) {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                System.err.println("WARN could not delete temporary trip output: " + path + " (" + e.getMessage() + ")");
            }
        }
    }
    private static void openFolder(String folderPath) {
        try {
            Path folder = Paths.get(folderPath).toAbsolutePath();

            if (!Files.exists(folder)) {
                System.err.println("Folder does not exist: " + folder);
                return;
            }

            if (!Files.isDirectory(folder)) {
                System.err.println("Path is not a directory: " + folder);
                return;
            }

            new ProcessBuilder(
                    "explorer.exe",
                    "/n,",
                    folder.toString()
            ).start();

        } catch (IOException e) {
            System.err.println("Failed to open File Explorer: " + e.getMessage());
            e.printStackTrace();
        }
    }

}
