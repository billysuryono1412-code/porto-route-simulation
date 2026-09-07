package porto.sweep.sim;

import porto.sweep.config.SweepConfig;
import porto.sweep.config.TaxiClassProfile;
import porto.sweep.eval.JaccardEvaluator;
import porto.sweep.io.DataRepository;
import porto.sweep.model.EdgeStats;
import porto.sweep.model.GraphEdge;
import porto.sweep.model.RouteData;
import porto.sweep.model.StableRouteRef;
import porto.sweep.model.TripOutcome;

import java.util.*;
import java.util.function.ToDoubleFunction;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;


public class SimulationEngine {
    private static class RerouteDiagnostics {
        long checks = 0;

        long maxBlocked = 0;
        long cooldownBlocked = 0;
        long finishedPathBlocked = 0;
        long emptyRemainingBlocked = 0;

        long triggerEvaluated = 0;
        long triggerFailed = 0;
        long triggered = 0;

        long noCandidate = 0;

        long gainEvaluated = 0;
        long gainFailed = 0;

        long applied = 0;

        double sumRouteRatio = 0.0;
        double sumRouteExtraSec = 0.0;
        double maxRouteRatio = 0.0;
        double maxRouteExtraSec = 0.0;

        double sumImprovementSec = 0.0;
        double maxImprovementSec = Double.NEGATIVE_INFINITY;

        double meanRouteRatio() {
            return triggerEvaluated == 0 ? 0.0 : sumRouteRatio / triggerEvaluated;
        }

        double meanRouteExtraSec() {
            return triggerEvaluated == 0 ? 0.0 : sumRouteExtraSec / triggerEvaluated;
        }

        double meanImprovementSec() {
            return gainEvaluated == 0 ? 0.0 : sumImprovementSec / gainEvaluated;
        }
    }

    private final DataRepository repo;
    private final SweepConfig config;

    // One SimulationEngine instance executes one sweep job at a time, so the
    // active run parameters are safe to use as global network-physics settings.
    private SweepParameters activeRunParams;

    private final Map<String, List<RouteData>> learnedCandidateRoutesByOd = new LinkedHashMap<>();
    private final Map<String, Set<String>> candidateRouteSignaturesByOd = new HashMap<>();
    private int learnedRouteSerial = 0;

    public SimulationEngine(DataRepository repo, SweepConfig config) {
        this.repo = repo;
        this.config = config;
    }


    private List<RouteData> candidatesForOd(String odId) {
        List<RouteData> base = repo.getCandidateRoutesByOd().getOrDefault(odId, List.of());
        List<RouteData> learned = learnedCandidateRoutesByOd.get(odId);
        if (learned == null || learned.isEmpty()) {
            return base;
        }
        List<RouteData> combined = new ArrayList<>(base.size() + learned.size());
        combined.addAll(base);
        combined.addAll(learned);
        return combined;
    }

    private Set<String> routeSignaturesForOd(String odId) {
        return candidateRouteSignaturesByOd.computeIfAbsent(odId, key -> {
            Set<String> out = new LinkedHashSet<>();
            for (RouteData route : repo.getCandidateRoutesByOd().getOrDefault(key, List.of())) {
                out.add(edgeSeqSignature(route.getEdgeSeq()));
            }
            return out;
        });
    }

    private static String edgeSeqSignature(List<String> edgeSeq) {
        if (edgeSeq == null || edgeSeq.isEmpty()) {
            return "";
        }
        return String.join(";", edgeSeq);
    }

    private void learnObservedRouteAsCandidate(TaxiState taxi) {
        if (!config.learnObservedRoutesAsCandidates) {
            return;
        }
        if (taxi == null || taxi.odId == null || taxi.odId.isBlank()) {
            return;
        }
        if (taxi.realizedEdges == null || taxi.realizedEdges.size() < config.learnedRouteMinEdges) {
            return;
        }
        if (taxi.realizedNodes == null || taxi.realizedNodes.size() != taxi.realizedEdges.size() + 1) {
            return;
        }

        String odId = taxi.odId;
        List<RouteData> learned = learnedCandidateRoutesByOd.computeIfAbsent(odId, k -> new ArrayList<>());
        if (learned.size() >= config.learnedRouteMaxPerOd) {
            return;
        }

        List<String> edgeSeq = new ArrayList<>(taxi.realizedEdges);
        String signature = edgeSeqSignature(edgeSeq);
        if (signature.isBlank()) {
            return;
        }

        Set<String> known = routeSignaturesForOd(odId);
        if (!known.add(signature)) {
            return;
        }

        List<String> nodeSeq = new ArrayList<>(taxi.realizedNodes);
        String routeId = String.format(Locale.US, "%s_learned_%05d", odId, ++learnedRouteSerial);
        double avgTimeSec = Math.max(1.0, taxi.accumulatedTripTimeSec);
        double avgDistanceM = estimateLearnedRouteDistanceM(odId, edgeSeq);
        int routeRank = 1_000_000 + learned.size();

        RouteData learnedRoute = new RouteData(
                odId,
                routeId,
                0.0,
                avgTimeSec,
                avgDistanceM,
                routeRank,
                nodeSeq,
                edgeSeq
        );
        learned.add(learnedRoute);
    }

    private double estimateLearnedRouteDistanceM(String odId, List<String> edgeSeq) {
        if (edgeSeq == null || edgeSeq.isEmpty()) {
            return 0.0;
        }
        double metersPerEdge = averageMetersPerEdge(repo.getCandidateRoutesByOd().getOrDefault(odId, List.of()));
        if (!Double.isFinite(metersPerEdge) || metersPerEdge <= 0.0) {
            metersPerEdge = averageMetersPerEdgeAllOds();
        }
        if (!Double.isFinite(metersPerEdge) || metersPerEdge <= 0.0) {
            metersPerEdge = 100.0;
        }
        return metersPerEdge * edgeSeq.size();
    }

    private double averageMetersPerEdgeAllOds() {
        double sum = 0.0;
        int n = 0;
        for (List<RouteData> routes : repo.getCandidateRoutesByOd().values()) {
            double value = averageMetersPerEdge(routes);
            if (Double.isFinite(value) && value > 0.0) {
                sum += value;
                n++;
            }
        }
        return n == 0 ? 0.0 : sum / n;
    }

    private static double averageMetersPerEdge(List<RouteData> routes) {
        if (routes == null || routes.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        int n = 0;
        for (RouteData route : routes) {
            if (route == null || route.getEdgeSeq().isEmpty()) {
                continue;
            }
            double distance = route.getAvgDistanceM();
            if (Double.isFinite(distance) && distance > 0.0) {
                sum += distance / Math.max(1, route.getEdgeSeq().size());
                n++;
            }
        }
        return n == 0 ? 0.0 : sum / n;
    }

    private record TripRequest(String odId, double requestedStartTimeSec) {
    }

    private void writeProgressHeader(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            if (!Files.exists(path)) {
                Files.writeString(
                        path,
                        "wall_elapsed_sec,completed_trips,target_trips,pct_complete,sim_time_sec,sim_clock," +
                                "remaining_requests,event_queue_size,mean_actual_min,mean_reroutes,trips_per_wall_sec,eta_wall_sec,param_id" +
                                System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }
        } catch (IOException e) {
            System.err.println("WARN could not create progress log: " + e.getMessage());
        }
    }

    private void logProgress(Path path,
                             SweepParameters params,
                             int completedTrips,
                             int targetTrips,
                             double simTimeSec,
                             int remainingRequests,
                             int eventQueueSize,
                             long runtimeStartNs,
                             double sumActualSec,
                             double sumReroutes) {
        double wallElapsedSec = (System.nanoTime() - runtimeStartNs) / 1_000_000_000.0;
        double pct = targetTrips <= 0 ? 100.0 : 100.0 * completedTrips / targetTrips;
        double tripsPerWallSec = wallElapsedSec <= 0.0 ? 0.0 : completedTrips / wallElapsedSec;
        double etaWallSec = tripsPerWallSec <= 0.0 ? Double.NaN : (targetTrips - completedTrips) / tripsPerWallSec;
        double meanActualMin = completedTrips <= 0 ? 0.0 : (sumActualSec / completedTrips) / 60.0;
        double meanReroutes = completedTrips <= 0 ? 0.0 : sumReroutes / completedTrips;

        String msg = String.format(
                Locale.US,
                "PROGRESS trips=%d/%d %.2f%% | sim=%s | wall=%.1f min | speed=%.2f trips/s | ETA=%.1f min | meanTrip=%.2f min | meanReroute=%.3f | queue=%d | reqLeft=%d",
                completedTrips,
                targetTrips,
                pct,
                formatClock(simTimeSec),
                wallElapsedSec / 60.0,
                tripsPerWallSec,
                etaWallSec / 60.0,
                meanActualMin,
                meanReroutes,
                eventQueueSize,
                remainingRequests
        );
        System.out.println(msg);

        String row = String.format(
                Locale.US,
                "%.3f,%d,%d,%.6f,%.3f,\"%s\",%d,%d,%.6f,%.6f,%.6f,%.3f,\"%s\"%s",
                wallElapsedSec,
                completedTrips,
                targetTrips,
                pct,
                simTimeSec,
                formatClock(simTimeSec),
                remainingRequests,
                eventQueueSize,
                meanActualMin,
                meanReroutes,
                tripsPerWallSec,
                etaWallSec,
                params.id(),
                System.lineSeparator()
        );

        try {
            Files.writeString(
                    path,
                    row,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            System.err.println("WARN could not append progress log: " + e.getMessage());
        }
    }

    private static String formatClock(double timeSec) {
        if (!Double.isFinite(timeSec)) {
            return "";
        }
        long total = Math.max(0L, Math.round(timeSec));
        long day = total / 86400L;
        long secOfDay = total % 86400L;
        long h = secOfDay / 3600L;
        long m = (secOfDay % 3600L) / 60L;
        long s = secOfDay % 60L;
        return String.format(Locale.US, "D%d %02d:%02d:%02d", day, h, m, s);
    }

    private static String safeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "run";
        }
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }


    private SweepParameters effectiveParams(TaxiState taxi, SweepParameters fallback) {
        if (taxi == null || taxi.taxiClassProfile == null) {
            return fallback;
        }
        return taxi.taxiClassProfile.resolve(fallback);
    }

    private double effectiveMemoryLearningRate(TaxiState taxi) {
        if (taxi == null || taxi.taxiClassProfile == null) {
            return config.memoryLearningRate;
        }
        return taxi.taxiClassProfile.resolveMemoryLearningRate(config.memoryLearningRate);
    }

    /**
     * Creates an exact-size taxi-class allocation using largest remainders, then
     * shuffles it with a dedicated seed so class assignment does not alter the
     * simulation's route-choice random stream.
     */
    private List<TaxiClassProfile> buildTaxiClassAssignments(int nTaxis, long seed) {
        List<TaxiClassProfile> profiles = config.taxiClasses;
        if (profiles == null || profiles.isEmpty()) {
            return new ArrayList<>(Collections.nCopies(nTaxis, null));
        }

        double shareSum = 0.0;
        for (TaxiClassProfile profile : profiles) {
            shareSum += profile.share;
        }
        if (!Double.isFinite(shareSum) || shareSum <= 0.0) {
            throw new IllegalStateException("Taxi-class shares must sum to a positive value.");
        }

        int[] counts = new int[profiles.size()];
        double[] remainders = new double[profiles.size()];
        int assigned = 0;
        for (int i = 0; i < profiles.size(); i++) {
            double exact = nTaxis * profiles.get(i).share / shareSum;
            counts[i] = (int) Math.floor(exact);
            remainders[i] = exact - counts[i];
            assigned += counts[i];
        }

        while (assigned < nTaxis) {
            int best = 0;
            for (int i = 1; i < remainders.length; i++) {
                if (remainders[i] > remainders[best]) {
                    best = i;
                }
            }
            counts[best]++;
            remainders[best] = -1.0;
            assigned++;
        }

        List<TaxiClassProfile> allocation = new ArrayList<>(nTaxis);
        for (int i = 0; i < profiles.size(); i++) {
            for (int j = 0; j < counts[i]; j++) {
                allocation.add(profiles.get(i));
            }
        }
        Collections.shuffle(allocation, new Random(seed ^ 0x9E3779B97F4A7C15L));
        return allocation;
    }

    public SweepRunResult run(SweepParameters params, long seed) {
        try {
            return runInternal(params, seed, null);
        } catch (IOException e) {
            throw new UncheckedIOException("Unexpected trip-output write failure", e);
        }
    }

    public SweepRunResult run(SweepParameters params, long seed, Path tripOutcomesCsv) throws IOException {
        if (tripOutcomesCsv == null) {
            return runInternal(params, seed, null);
        }
        if (tripOutcomesCsv.getParent() != null) {
            Files.createDirectories(tripOutcomesCsv.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(
                tripOutcomesCsv,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            writer.write(TripOutcome.csvHeader());
            writer.newLine();
            return runInternal(params, seed, writer);
        }
    }

    private SweepRunResult runInternal(SweepParameters params, long seed, BufferedWriter outcomeWriter) throws IOException {
        activeRunParams = params;
        learnedCandidateRoutesByOd.clear();
        candidateRouteSignaturesByOd.clear();
        learnedRouteSerial = 0;

        RerouteDiagnostics rrDiag = new RerouteDiagnostics();
        long runtimeStartNs = System.nanoTime();
        long outcomeWriteNs = 0L;
        Path progressPath = config.outputDir.resolve("progress_" + safeFileName(params.id()) + ".csv");
        writeProgressHeader(progressPath);

        long lastProgressNs = runtimeStartNs;
        int lastProgressTrips = 0;
        double sumJ = 0.0;
        double sumActual = 0.0;
        double sumReroutes = 0.0;
        int completedTrips = 0;
        int dominantMatches = 0;
        int dominantComparableTrips = 0;
        double lastFinishTimeSec = 0.0;
        ObjectiveMetricsAccumulator objectiveAccumulator = new ObjectiveMetricsAccumulator(repo, config);

        Random rng = new Random(seed);
        int targetTrips = resolveTargetTrips();
        Deque<TripRequest> tripRequests = buildTripRequestQueue(targetTrips, rng);
        Map<String, Integer> liveEdgeFlow = new HashMap<>();
        Map<String, Integer> collectiveEdgeHabitCounts = new HashMap<>();

        List<TaxiState> taxis = new ArrayList<>();
        PriorityQueue<TaxiState> pq = new PriorityQueue<>(Comparator.comparingDouble(t -> t.nextEventTimeSec));

        List<TaxiClassProfile> taxiClassAssignments = buildTaxiClassAssignments(config.nTaxis, seed);
        Map<String, Integer> taxiClassCounts = new TreeMap<>();

        for (int i = 0; i < config.nTaxis; i++) {
            TaxiState taxi = new TaxiState(i, taxiClassAssignments.get(i));
            taxiClassCounts.put(taxi.getTaxiClassId(), taxiClassCounts.getOrDefault(taxi.getTaxiClassId(), 0) + 1);
            taxis.add(taxi);
            if (!tripRequests.isEmpty()) {
                TripRequest req = tripRequests.removeFirst();
                dispatchNextRequest(taxi, req, 0.0, params, liveEdgeFlow,
                        collectiveEdgeHabitCounts, rng);
                if (taxi.mode != TaxiState.Mode.IDLE) {
                    pq.add(taxi);
                }
            }
        }

        System.out.println("Taxi class allocation: " + taxiClassCounts);

        while (!pq.isEmpty() && completedTrips < targetTrips) {
            TaxiState taxi = pq.poll();
            double nowSec = taxi.nextEventTimeSec;

            if (taxi.mode == TaxiState.Mode.WAITING) {
                assignServiceTrip(taxi, taxi.odId, nowSec, taxi.requestedStartTimeSec, params,
                        liveEdgeFlow, collectiveEdgeHabitCounts, rng);
                if (taxi.mode != TaxiState.Mode.IDLE) {
                    pq.add(taxi);
                }
                continue;
            }

            if (!taxi.traversingEdgeId.isEmpty()) {
                decrementFlow(liveEdgeFlow, taxi.traversingEdgeId);
            }

            taxi.currentNode = taxi.plannedNodes.get(Math.min(taxi.nextEdgeIndex + 1, taxi.plannedNodes.size() - 1));
            double edgeTravelSec = Math.max(0.0, nowSec - taxi.stateStartTimeSec);
            taxi.accumulatedTripTimeSec += edgeTravelSec;
            taxi.stateStartTimeSec = nowSec;

            if (taxi.mode == TaxiState.Mode.SERVING) {
                if (taxi.realizedNodes.isEmpty()) {
                    taxi.realizedNodes.add(taxi.plannedNodes.get(0));
                }
                taxi.realizedEdges.add(taxi.traversingEdgeId);
                taxi.realizedEdgeTravelSec.add(edgeTravelSec);
                taxi.realizedNodes.add(taxi.currentNode);
            }

            taxi.nextEdgeIndex++;

            boolean finishedPath = taxi.nextEdgeIndex >= taxi.plannedEdges.size();
            if (finishedPath) {
                taxi.nextEventTimeSec = Double.POSITIVE_INFINITY;
                taxi.traversingEdgeId = "";

                if (taxi.mode == TaxiState.Mode.REPOSITIONING) {
                    String odId = taxi.odId;
                    startServiceOrWait(taxi, odId, taxi.requestedStartTimeSec, nowSec, params,
                            liveEdgeFlow, collectiveEdgeHabitCounts, rng);
                    if (taxi.mode != TaxiState.Mode.IDLE) {
                        pq.add(taxi);
                    }
                } else if (taxi.mode == TaxiState.Mode.SERVING) {
                    taxi.completedTrips++;
                    TripOutcome outcome = buildOutcome(taxi);
                    completedTrips++;
                    lastFinishTimeSec = outcome.getFinishTimeSec();

                    if (outcomeWriter != null) {
                        long writeStartNs = System.nanoTime();
                        outcomeWriter.write(outcome.toCsvRow());
                        outcomeWriter.newLine();
                        outcomeWriteNs += System.nanoTime() - writeStartNs;
                    }

                    sumJ += outcome.getBestJaccard();
                    sumActual += outcome.getActualTimeSec();
                    sumReroutes += outcome.getRerouteCount();
                    objectiveAccumulator.add(outcome);

                    String odId = outcome.getOdId();
                    if (odId != null && !odId.isBlank()) {
                        String matchedRouteId = outcome.getMatchedRealRouteId();
                        String dominantRouteId = dominantStableRouteId(odId);
                        if (JaccardEvaluator.MATCHED.equals(outcome.getRouteMatchStatus())
                                && !dominantRouteId.isBlank()
                                && matchedRouteId != null
                                && !matchedRouteId.isBlank()) {
                            dominantComparableTrips++;
                            if (dominantRouteId.equals(matchedRouteId)) {
                                dominantMatches++;
                            }
                        }
                    }

                    learnObservedRouteAsCandidate(taxi);
                    updateMemoryFromTrip(taxi, liveEdgeFlow);
                    recordEdgeHabit(taxi, collectiveEdgeHabitCounts, taxi.realizedEdges);

                    if (!tripRequests.isEmpty() && completedTrips < targetTrips) {
                        TripRequest nextReq = tripRequests.removeFirst();
                        dispatchNextRequest(taxi, nextReq, nowSec, params, liveEdgeFlow,
                                collectiveEdgeHabitCounts, rng);
                        if (taxi.mode != TaxiState.Mode.IDLE) {
                            pq.add(taxi);
                        }
                    } else {
                        taxi.mode = TaxiState.Mode.IDLE;
                    }
                }
                continue;
            }

            if (taxi.mode == TaxiState.Mode.SERVING) {
                maybeReroute(taxi, nowSec, params, liveEdgeFlow, rng, rrDiag);
            }

            scheduleNextEdgeEntry(taxi, nowSec, liveEdgeFlow);
            if (Double.isFinite(taxi.nextEventTimeSec)) {
                pq.add(taxi);
            }
            long nowNsForProgress = System.nanoTime();
            double secSinceLastProgress = (nowNsForProgress - lastProgressNs) / 1_000_000_000.0;

            if (secSinceLastProgress >= 30.0 || completedTrips - lastProgressTrips >= 10000) {
                logProgress(
                        progressPath,
                        params,
                        completedTrips,
                        targetTrips,
                        nowSec,
                        tripRequests.size(),
                        pq.size(),
                        runtimeStartNs,
                        sumActual,
                        sumReroutes
                );

                lastProgressNs = nowNsForProgress;
                lastProgressTrips = completedTrips;
            }
        }

        int n = Math.max(1, completedTrips);
        double completionRate = targetTrips <= 0 ? 1.0 : (double) completedTrips / (double) targetTrips;
        double dominantRouteMatchRate = dominantComparableTrips == 0
                ? 0.0
                : dominantMatches / (double) dominantComparableTrips;
        ObjectiveMetricsAccumulator.ObjectiveMetrics objectiveMetrics = objectiveAccumulator.finish(completionRate);
        double runtimeSec = Math.max(0L, System.nanoTime() - runtimeStartNs - outcomeWriteNs) / 1_000_000_000.0;

        logProgress(
                progressPath,
                params,
                completedTrips,
                targetTrips,
                lastFinishTimeSec,
                tripRequests.size(),
                pq.size(),
                runtimeStartNs,
                sumActual,
                sumReroutes
        );

        return new SweepRunResult(
                params,
                completedTrips,
                sumJ / n,
                objectiveMetrics.meanRouteMatchScore,
                sumActual / n,
                sumReroutes / n,
                completionRate,
                dominantRouteMatchRate,
                objectiveMetrics.finalRouteShareJsd,
                objectiveMetrics.finalRouteShareMae,
                objectiveMetrics.edgeFlowWmae,
                objectiveMetrics.travelTimeWmaeSec,
                objectiveMetrics.simulatedNoveltyRate,
                objectiveMetrics.observedNoveltyRate,
                objectiveMetrics.noveltyRateError,
                objectiveMetrics.normalizedFinalRouteShareError,
                objectiveMetrics.normalizedEdgeFlowError,
                objectiveMetrics.normalizedTravelTimeError,
                objectiveMetrics.normalizedNoveltyError,
                objectiveMetrics.objectiveLoss,
                config.objectiveEnabled,
                objectiveMetrics.objectiveEligible,
                objectiveMetrics.matchedTripCount,
                objectiveMetrics.unmatchedTripCount,
                objectiveMetrics.ambiguousTripCount,
                objectiveMetrics.noReferenceTripCount,
                objectiveMetrics.travelTimeComparableTripCount,
                runtimeSec
        );
    }

    private String dominantStableRouteId(String odId) {
        List<StableRouteRef> refs = repo.getStableRoutesByOd().getOrDefault(odId, List.of());
        String bestRouteId = "";
        double bestShare = Double.NEGATIVE_INFINITY;
        for (StableRouteRef ref : refs) {
            if (ref.getStableShare() > bestShare) {
                bestShare = ref.getStableShare();
                bestRouteId = ref.getRouteId();
            }
        }
        return bestRouteId;
    }

    private int resolveTargetTrips() {
        if (config.totalTrips > 0) {
            return config.totalTrips;
        }
        int sum = repo.getOdTripCount().values().stream().mapToInt(Integer::intValue).sum();
        return Math.max(1, sum);
    }

    private Deque<TripRequest> buildTripRequestQueue(int targetTrips, Random rng) {
        List<String> odBag = buildOdBag(targetTrips, rng);
        List<Double> requestTimes = buildRequestTimes(targetTrips, rng);
        requestTimes.sort(Double::compareTo);

        List<TripRequest> requests = new ArrayList<>();
        for (int i = 0; i < targetTrips; i++) {
            requests.add(new TripRequest(odBag.get(i), requestTimes.get(i)));
        }
        requests.sort(Comparator.comparingDouble(r -> r.requestedStartTimeSec));
        return new ArrayDeque<>(requests);
    }

    private List<String> buildOdBag(int targetTrips, Random rng) {
        List<String> bag = new ArrayList<>();
        for (Map.Entry<String, List<StableRouteRef>> entry : repo.getStableRoutesByOd().entrySet()) {
            String odId = entry.getKey();
            int count = repo.getOdTripCount().getOrDefault(odId, 1);
            if (config.maxTripsPerOd > 0) {
                count = Math.min(count, config.maxTripsPerOd);
            }
            count = Math.max(1, count);
            for (int i = 0; i < count; i++) {
                bag.add(odId);
            }
        }
        if (bag.isEmpty()) {
            bag.addAll(repo.getCandidateRoutesByOd().keySet());
        }
        if (bag.isEmpty()) {
            throw new IllegalStateException("No OD pairs are available for simulation.");
        }
        while (bag.size() < targetTrips) {
            bag.add(bag.get(rng.nextInt(bag.size())));
        }
        java.util.Collections.shuffle(bag, rng);
        return new ArrayList<>(bag.subList(0, targetTrips));
    }

    private List<Double> buildRequestTimes(int targetTrips, Random rng) {
        List<Double> times = new ArrayList<>(targetTrips);
        if (!"hourly".equals(config.tripStartMode)) {
            for (int i = 0; i < targetTrips; i++) {
                times.add(0.0);
            }
            return times;
        }

        double[] cumulative = new double[24];
        double sum = 0.0;
        for (int h = 0; h < 24; h++) {
            sum += Math.max(0.0, config.hourlyDemandWeights.get(h));
            cumulative[h] = sum;
        }
        if (sum <= 0.0) {
            throw new IllegalStateException("hourlyDemandWeights sum must be positive.");
        }

        for (int i = 0; i < targetTrips; i++) {
            int day = rng.nextInt(Math.max(1, config.simulationDays));
            double u = rng.nextDouble() * sum;
            int hour = 0;
            while (hour < 23 && cumulative[hour] < u) {
                hour++;
            }
            double secWithinHour = rng.nextDouble() * 3600.0;
            double t = day * 86400.0 + hour * 3600.0 + secWithinHour;
            times.add(t);
        }
        return times;
    }

    private void dispatchNextRequest(TaxiState taxi, TripRequest req, double nowSec, SweepParameters params,
                                     Map<String, Integer> liveEdgeFlow,
                                     Map<String, Integer> collectiveEdgeHabitCounts,
                                     Random rng) {
        List<RouteData> candidates = candidatesForOd(req.odId);
        if (candidates == null || candidates.isEmpty()) {
            taxi.mode = TaxiState.Mode.IDLE;
            return;
        }

        String nextOrigin = candidates.get(0).getOriginNode();
        if (taxi.currentNode == null || taxi.currentNode.isBlank() || taxi.currentNode.equals(nextOrigin)) {
            taxi.currentNode = nextOrigin;
            startServiceOrWait(taxi, req.odId, req.requestedStartTimeSec, nowSec, params,
                    liveEdgeFlow, collectiveEdgeHabitCounts, rng);
            return;
        }

        PathResult reposition = dijkstra(repo.getGlobalGraph(), taxi.currentNode, nextOrigin, nowSec, liveEdgeFlow, taxi,
                true, null);
        if (reposition == null || reposition.edgeSeq.isEmpty()) {
            taxi.currentNode = nextOrigin;
            startServiceOrWait(taxi, req.odId, req.requestedStartTimeSec, nowSec, params,
                    liveEdgeFlow, collectiveEdgeHabitCounts, rng);
            return;
        }

        taxi.mode = TaxiState.Mode.REPOSITIONING;
        taxi.odId = req.odId;
        taxi.requestedStartTimeSec = req.requestedStartTimeSec;
        taxi.destinationNode = nextOrigin;
        taxi.plannedNodes = reposition.nodeSeq;
        taxi.plannedEdges = reposition.edgeSeq;
        taxi.nextEdgeIndex = 0;
        taxi.stateStartTimeSec = nowSec;
        taxi.accumulatedTripTimeSec = 0.0;
        taxi.rerouteCount = 0;
        taxi.lastRerouteDecisionTimeSec = -1e18;
        scheduleNextEdgeEntry(taxi, nowSec, liveEdgeFlow);
    }

    private void startServiceOrWait(TaxiState taxi, String odId, double requestedStartTimeSec, double nowSec,
                                    SweepParameters params,
                                    Map<String, Integer> liveEdgeFlow,
                                    Map<String, Integer> collectiveEdgeHabitCounts,
                                    Random rng) {
        double serviceStartSec = Math.max(nowSec, requestedStartTimeSec);
        if (serviceStartSec > nowSec + 1e-9) {
            taxi.resetForWaiting(odId, requestedStartTimeSec, serviceStartSec);
            return;
        }
        assignServiceTrip(taxi, odId, serviceStartSec, requestedStartTimeSec, params,
                liveEdgeFlow, collectiveEdgeHabitCounts, rng);
    }

    private void assignServiceTrip(TaxiState taxi, String odId, double nowSec, double requestedStartTimeSec,
                                   SweepParameters params,
                                   Map<String, Integer> liveEdgeFlow,
                                   Map<String, Integer> collectiveEdgeHabitCounts,
                                   Random rng) {
        List<RouteData> candidates = candidatesForOd(odId);
        if (candidates == null || candidates.isEmpty()) {
            taxi.mode = TaxiState.Mode.IDLE;
            return;
        }
        RouteData chosen = chooseRoute(candidates, taxi, nowSec, params, liveEdgeFlow,
                collectiveEdgeHabitCounts, rng);
        taxi.currentNode = chosen.getOriginNode();
        taxi.resetForService(odId, chosen.getRouteId(), chosen.getDestinationNode(), nowSec, requestedStartTimeSec);
        taxi.plannedNodes = new ArrayList<>(chosen.getNodeSeq());
        taxi.plannedEdges = new ArrayList<>(chosen.getEdgeSeq());
        taxi.nextEdgeIndex = 0;
        taxi.expectedTripTimeSec = estimatePathCost(chosen.getEdgeSeq(), nowSec, liveEdgeFlow, taxi, false);
        scheduleNextEdgeEntry(taxi, nowSec, liveEdgeFlow);
    }

    private RouteData chooseRoute(List<RouteData> candidates, TaxiState taxi, double nowSec,
                                  SweepParameters params,
                                  Map<String, Integer> liveEdgeFlow,
                                  Map<String, Integer> collectiveEdgeHabitCounts,
                                  Random rng) {
        params = effectiveParams(taxi, params);
        if (!SweepParameters.CHOICE_BEHAVIORAL.equals(params.routeChoiceMode)) {
            return chooseBenchmarkRoute(candidates, taxi, nowSec, params.routeChoiceMode, liveEdgeFlow);
        }
        RouteData best = candidates.get(0);
        double bestScore = Double.NEGATIVE_INFINITY;
        Map<String, Integer> edgeHabitCounts = edgeHabitCountsForTaxi(taxi, collectiveEdgeHabitCounts);

        List<RouteChoiceFeatures> features = new ArrayList<>();
        for (RouteData route : candidates) {
            features.add(new RouteChoiceFeatures(
                    route,
                    estimatePathCost(route.getEdgeSeq(), nowSec, liveEdgeFlow, taxi, false),
                    averageMemoryPenalty(route.getEdgeSeq(), taxi),
                    edgeHabitScore(route.getEdgeSeq(), edgeHabitCounts),
                    distanceDetourLogRatio(route, candidates),
                    routeComplexityLogRatio(route, candidates)
            ));
        }

        double meanExpected = mean(features, f -> f.expectedSec);
        double stdExpected = std(features, f -> f.expectedSec, meanExpected);
        double meanMemory = mean(features, f -> f.memoryPenaltySec);
        double stdMemory = std(features, f -> f.memoryPenaltySec, meanMemory);
        double meanEdgeHabit = mean(features, f -> f.edgeHabitScore);
        double stdEdgeHabit = std(features, f -> f.edgeHabitScore, meanEdgeHabit);
        double meanDetour = mean(features, f -> f.detourLogRatio);
        double stdDetour = std(features, f -> f.detourLogRatio, meanDetour);
        double meanComplexity = mean(features, f -> f.complexityLogRatio);
        double stdComplexity = std(features, f -> f.complexityLogRatio, meanComplexity);

        for (RouteChoiceFeatures f : features) {
            double expectedTerm = config.normalizeChoiceUtility
                    ? zScore(f.expectedSec, meanExpected, stdExpected)
                    : f.expectedSec;
            double memoryTerm = config.normalizeChoiceUtility
                    ? zScore(f.memoryPenaltySec, meanMemory, stdMemory)
                    : f.memoryPenaltySec;
            double edgeHabitTerm = config.normalizeChoiceUtility
                    ? zScore(f.edgeHabitScore, meanEdgeHabit, stdEdgeHabit)
                    : f.edgeHabitScore;
            double detourTerm = config.normalizeChoiceUtility
                    ? zScore(f.detourLogRatio, meanDetour, stdDetour)
                    : f.detourLogRatio;
            double complexityTerm = config.normalizeChoiceUtility
                    ? zScore(f.complexityLogRatio, meanComplexity, stdComplexity)
                    : f.complexityLogRatio;

            double noise = rng.nextGaussian() * params.decisionNoiseStd;
            double score = -params.betaTime * expectedTerm
                    - params.betaMemory * memoryTerm
                    + params.betaEdgeHabit * edgeHabitTerm
                    - params.betaDetour * detourTerm
                    - params.betaComplexity * complexityTerm
                    + noise;
            if (score > bestScore) {
                bestScore = score;
                best = f.route;
            }
        }
        return best;
    }

    /**
     * Deterministic route-choice modes. They are used by sweepMode=benchmark,
     * and dynamic_fastest can also be selected in grid/LHS mode with rerouting
     * enabled. These bypass behavioral utility, memory/habit coefficients and
     * decision noise for the initial route decision.
     */
    private RouteData chooseBenchmarkRoute(List<RouteData> candidates,
                                           TaxiState taxi,
                                           double nowSec,
                                           String mode,
                                           Map<String, Integer> liveEdgeFlow) {
        RouteData best = candidates.get(0);
        double bestValue = benchmarkRouteCost(best, taxi, nowSec, mode, liveEdgeFlow);

        for (int i = 1; i < candidates.size(); i++) {
            RouteData route = candidates.get(i);
            double value = benchmarkRouteCost(route, taxi, nowSec, mode, liveEdgeFlow);
            if (value < bestValue
                    || (Double.compare(value, bestValue) == 0
                    && route.getRouteId().compareTo(best.getRouteId()) < 0)) {
                best = route;
                bestValue = value;
            }
        }
        return best;
    }

    private double benchmarkRouteCost(RouteData route,
                                      TaxiState taxi,
                                      double nowSec,
                                      String mode,
                                      Map<String, Integer> liveEdgeFlow) {
        return switch (mode) {
            case SweepParameters.CHOICE_SHORTEST -> {
                double distance = route.getAvgDistanceM();
                yield Double.isFinite(distance) && distance > 0.0
                        ? distance
                        : Math.max(1, route.getEdgeSeq().size());
            }
            case SweepParameters.CHOICE_STATIC_FASTEST -> {
                double time = route.getAvgTimeSec();
                yield Double.isFinite(time) && time > 0.0
                        ? time
                        : estimatePathBaseCost(route.getEdgeSeq(), nowSec);
            }
            case SweepParameters.CHOICE_DYNAMIC_FASTEST ->
                    estimatePathTrafficCost(route.getEdgeSeq(), nowSec, liveEdgeFlow);
            default -> throw new IllegalArgumentException("Unsupported benchmark routeChoiceMode: " + mode);
        };
    }

    private static class RouteChoiceFeatures {
        final RouteData route;
        final double expectedSec;
        final double memoryPenaltySec;
        final double edgeHabitScore;
        final double detourLogRatio;
        final double complexityLogRatio;

        RouteChoiceFeatures(RouteData route, double expectedSec, double memoryPenaltySec,
                            double edgeHabitScore, double detourLogRatio, double complexityLogRatio) {
            this.route = route;
            this.expectedSec = expectedSec;
            this.memoryPenaltySec = memoryPenaltySec;
            this.edgeHabitScore = edgeHabitScore;
            this.detourLogRatio = detourLogRatio;
            this.complexityLogRatio = complexityLogRatio;
        }
    }

    private static double mean(List<RouteChoiceFeatures> values, ToDoubleFunction<RouteChoiceFeatures> getter) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (RouteChoiceFeatures v : values) {
            sum += getter.applyAsDouble(v);
        }
        return sum / values.size();
    }

    private static double std(List<RouteChoiceFeatures> values, ToDoubleFunction<RouteChoiceFeatures> getter, double mean) {
        if (values.size() <= 1) {
            return 1.0;
        }
        double sumSq = 0.0;
        for (RouteChoiceFeatures v : values) {
            double d = getter.applyAsDouble(v) - mean;
            sumSq += d * d;
        }
        double variance = sumSq / Math.max(1, values.size() - 1);
        return Math.max(1e-9, Math.sqrt(variance));
    }

    private static double meanDouble(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    private static double stdDouble(List<Double> values, double mean) {
        if (values.size() <= 1) {
            return 1.0;
        }
        double sumSq = 0.0;
        for (double v : values) {
            double d = v - mean;
            sumSq += d * d;
        }
        double variance = sumSq / Math.max(1, values.size() - 1);
        return Math.max(1e-9, Math.sqrt(variance));
    }

    private static double zScore(double value, double mean, double std) {
        if (!Double.isFinite(value) || !Double.isFinite(mean) || !Double.isFinite(std) || std <= 1e-9) {
            return 0.0;
        }
        return (value - mean) / std;
    }

    private double averageMemoryPenalty(List<String> edgeSeq, TaxiState taxi) {
        if (edgeSeq.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (String edgeId : edgeSeq) {
            sum += taxi.getMemoryPenalty(edgeId);
        }
        return sum / edgeSeq.size();
    }

    private Map<String, Integer> edgeHabitCountsForTaxi(
            TaxiState taxi,
            Map<String, Integer> collectiveEdgeHabitCounts
    ) {
        if ("taxi".equals(config.edgeHabitMode)) {
            return taxi.edgeHabitCounts;
        }
        if ("collective".equals(config.edgeHabitMode)) {
            return collectiveEdgeHabitCounts;
        }
        return Collections.emptyMap();
    }

    private double edgeHabitScore(List<String> edgeSeq, Map<String, Integer> edgeHabitCounts) {
        if ("off".equals(config.edgeHabitMode) || edgeSeq == null || edgeSeq.isEmpty()) {
            return 0.0;
        }

        double scale = Math.max(1e-9, config.edgeHabitScale);
        double sum = 0.0;
        int n = 0;

        for (String edgeId : edgeSeq) {
            if (edgeId == null || edgeId.isBlank()) {
                continue;
            }
            int count = edgeHabitCounts.getOrDefault(edgeId, 0);
            sum += Math.log1p(count / scale);
            n++;
        }

        return n == 0 ? 0.0 : sum / n;
    }

    private void recordEdgeHabit(TaxiState taxi,
                                 Map<String, Integer> collectiveEdgeHabitCounts,
                                 List<String> realizedEdges) {
        if ("off".equals(config.edgeHabitMode) || realizedEdges == null || realizedEdges.isEmpty()) {
            return;
        }

        Map<String, Integer> target = edgeHabitCountsForTaxi(taxi, collectiveEdgeHabitCounts);
        for (String edgeId : realizedEdges) {
            if (edgeId == null || edgeId.isBlank()) {
                continue;
            }
            target.put(edgeId, target.getOrDefault(edgeId, 0) + 1);
        }
    }

    private double distanceDetourLogRatio(RouteData route, List<RouteData> candidates) {
        double routeDistance = route.getAvgDistanceM();

        if (!Double.isFinite(routeDistance) || routeDistance <= 0.0) {
            routeDistance = route.getEdgeSeq().size();
        }

        double minDistance = Double.POSITIVE_INFINITY;
        for (RouteData r : candidates) {
            double d = r.getAvgDistanceM();
            if (!Double.isFinite(d) || d <= 0.0) {
                d = r.getEdgeSeq().size();
            }
            if (d > 0.0) {
                minDistance = Math.min(minDistance, d);
            }
        }

        if (!Double.isFinite(minDistance) || minDistance <= 0.0) {
            return 0.0;
        }

        return Math.log(Math.max(1.0, routeDistance / minDistance));
    }

    private double routeComplexityLogRatio(RouteData route, List<RouteData> candidates) {
        double edgeCount = Math.max(1, route.getEdgeSeq().size());

        double minEdges = Double.POSITIVE_INFINITY;
        for (RouteData r : candidates) {
            double n = Math.max(1, r.getEdgeSeq().size());
            minEdges = Math.min(minEdges, n);
        }

        if (!Double.isFinite(minEdges) || minEdges <= 0.0) {
            return 0.0;
        }

        return Math.log(Math.max(1.0, edgeCount / minEdges));
    }

    private PathResult chooseBestRerouteSuffix(
            TaxiState taxi,
            String currentNode,
            String destNode,
            List<String> remainingCurrentEdges,
            List<String> visibleTriggerEdges,
            double nowSec,
            SweepParameters params,
            Map<String, Integer> liveEdgeFlow,
            Random rng
    ) {
        // If you already added repo.getRerouteTimeGraph(), use that here.
        // Otherwise use repo.getGlobalGraph() for now.
        List<PathResult> candidates = generateRerouteSuffixCandidates(
                currentNode,
                destNode,
                remainingCurrentEdges,
                visibleTriggerEdges,
                nowSec,
                taxi,
                liveEdgeFlow,
                config.rerouteTopK,
                config.rerouteMaxRelTime,
                config.rerouteMaxEdgeJaccard
        );
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        debug("REROUTE_CANDIDATES taxi=" + taxi.taxiId
                + " od=" + taxi.odId
                + " currentNode=" + currentNode
                + " destNode=" + destNode
                + " candidateCount=" + candidates.size());

        // A dynamic-fastest run uses the same principle after departure: among
        // the admissible reroute alternatives, choose the one with the lowest
        // current traffic travel time. Behavioral betas are intentionally ignored.
        if (SweepParameters.CHOICE_DYNAMIC_FASTEST.equals(params.routeChoiceMode)) {
            PathResult fastest = null;
            for (PathResult cand : candidates) {
                if (cand.edgeSeq == null || cand.edgeSeq.isEmpty()
                        || cand.edgeSeq.equals(remainingCurrentEdges)) {
                    continue;
                }
                if (fastest == null || cand.costSec < fastest.costSec) {
                    fastest = cand;
                }
            }
            return fastest;
        }

        PathResult best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        List<Double> expectedValues = new ArrayList<>();
        List<Double> memoryValues = new ArrayList<>();
        for (PathResult cand : candidates) {
            if (cand.edgeSeq == null || cand.edgeSeq.isEmpty() || cand.edgeSeq.equals(remainingCurrentEdges)) {
                continue;
            }
            expectedValues.add(cand.costSec);
            memoryValues.add(averageMemoryPenalty(cand.edgeSeq, taxi));
        }
        double meanExpected = meanDouble(expectedValues);
        double stdExpected = stdDouble(expectedValues, meanExpected);
        double meanMemory = meanDouble(memoryValues);
        double stdMemory = stdDouble(memoryValues, meanMemory);

        for (PathResult cand : candidates) {
            if (cand.edgeSeq == null || cand.edgeSeq.isEmpty()) {
                continue;
            }
            if (cand.edgeSeq.equals(remainingCurrentEdges)) {
                continue;
            }

            double expected = cand.costSec;
            double mem = averageMemoryPenalty(cand.edgeSeq, taxi);

            double expectedTerm = config.normalizeChoiceUtility
                    ? zScore(expected, meanExpected, stdExpected)
                    : expected;
            double memoryTerm = config.normalizeChoiceUtility
                    ? zScore(mem, meanMemory, stdMemory)
                    : mem;

            // Keep reroute deterministic first so you can debug it
            double noise = 0.0;

            double score = -params.betaTime * expectedTerm
                    - params.betaMemory * memoryTerm
                    + noise;

            if (score > bestScore) {
                bestScore = score;
                best = cand;
            }
        }

        return best;
    }

    private static class CongestedEdge {
        final String edgeId;
        final double extraSec;
        final double ratio;

        CongestedEdge(String edgeId, double extraSec, double ratio) {
            this.edgeId = edgeId;
            this.extraSec = extraSec;
            this.ratio = ratio;
        }
    }

    /**
     * Generates reroute candidates by targeting the congestion the driver can
     * currently see in the bounded look-ahead window. Instead of banning every
     * edge from every discovered path, the method ranks visible edges by added
     * delay and performs only a small, bounded number of searches.
     */
    private List<PathResult> generateRerouteSuffixCandidates(
            String currentNode,
            String destNode,
            List<String> remainingCurrentEdges,
            List<String> visibleTriggerEdges,
            double nowSec,
            TaxiState taxi,
            Map<String, Integer> liveEdgeFlow,
            int topK,
            double maxRelTime,
            double maxEdgeJaccard
    ) {
        Map<String, List<GraphEdge>> graph = repo.getRerouteTimeGraph();
        Map<String, List<GraphEdge>> reverseGraph = repo.getRerouteTimeReverseGraph();

        if (graph == null || graph.isEmpty() || reverseGraph == null || reverseGraph.isEmpty()) {
            return List.of();
        }
        if (currentNode == null || destNode == null || currentNode.isBlank() || destNode.isBlank()) {
            return List.of();
        }
        if (topK <= 0 || visibleTriggerEdges == null || visibleTriggerEdges.isEmpty()) {
            return List.of();
        }

        List<CongestedEdge> congested = rankVisibleCongestedEdges(
                visibleTriggerEdges, nowSec, taxi, liveEdgeFlow
        );
        if (congested.isEmpty()) {
            return List.of();
        }

        // Hard bound on expensive graph searches. For the normal topK=3 this
        // permits at most six bidirectional searches, versus the old 12+ path
        // budget and up to dozens of repeated Dijkstra attempts.
        int maxSearches = Math.max(1, topK * 2);
        List<Set<String>> banPlans = new ArrayList<>(maxSearches);

        // First try each visible congested edge independently, worst delay first.
        for (CongestedEdge edge : congested) {
            if (banPlans.size() >= maxSearches) {
                break;
            }
            banPlans.add(Set.of(edge.edgeId));
        }

        // If the look-ahead window contains fewer edges than the search budget,
        // use a few two-edge combinations anchored on the worst congested edge.
        // This can produce a third distinct alternative without returning to
        // arbitrary bans over every edge of newly discovered paths.
        if (congested.size() >= 2 && banPlans.size() < maxSearches) {
            String worst = congested.get(0).edgeId;
            for (int i = 1; i < congested.size() && banPlans.size() < maxSearches; i++) {
                LinkedHashSet<String> pair = new LinkedHashSet<>();
                pair.add(worst);
                pair.add(congested.get(i).edgeId);
                banPlans.add(pair);
            }
        }

        List<PathResult> raw = new ArrayList<>();
        Set<String> seenPathSigs = new HashSet<>();

        for (Set<String> banned : banPlans) {
            PathResult cand = bidirectionalDijkstraAvoidingEdges(
                    graph,
                    reverseGraph,
                    currentNode,
                    destNode,
                    nowSec,
                    liveEdgeFlow,
                    taxi,
                    false,
                    banned
            );
            if (cand == null || cand.edgeSeq == null || cand.edgeSeq.isEmpty()) {
                continue;
            }
            if (remainingCurrentEdges != null && cand.edgeSeq.equals(remainingCurrentEdges)) {
                continue;
            }
            if (!seenPathSigs.add(pathSignature(cand))) {
                continue;
            }
            raw.add(cand);
        }

        if (raw.isEmpty()) {
            return List.of();
        }

        raw.sort(Comparator.comparingDouble(p -> p.costSec));
        double bestCost = raw.get(0).costSec;

        List<PathResult> kept = new ArrayList<>();
        for (PathResult cand : raw) {
            if (cand.costSec > bestCost * maxRelTime) {
                continue;
            }

            boolean tooSimilar = false;
            for (PathResult prev : kept) {
                if (edgeJaccard(cand.edgeSeq, prev.edgeSeq) > maxEdgeJaccard) {
                    tooSimilar = true;
                    break;
                }
            }
            if (tooSimilar) {
                continue;
            }

            kept.add(cand);
            if (kept.size() >= topK) {
                break;
            }
        }

        if (config.debugRerouting) {
            StringBuilder ranked = new StringBuilder();
            for (int i = 0; i < congested.size(); i++) {
                CongestedEdge e = congested.get(i);
                if (i > 0) ranked.append(';');
                ranked.append(e.edgeId)
                        .append(" extra=")
                        .append(String.format(Locale.US, "%.2f", e.extraSec))
                        .append(" ratio=")
                        .append(String.format(Locale.US, "%.3f", e.ratio));
            }
            debug("REROUTE_CONGESTED_EDGES taxi=" + taxi.taxiId
                    + " od=" + taxi.odId
                    + " ranked=" + ranked
                    + " searches=" + banPlans.size()
                    + " rawCandidates=" + raw.size()
                    + " keptCandidates=" + kept.size());
        }

        return kept;
    }

    private List<CongestedEdge> rankVisibleCongestedEdges(
            List<String> visibleEdges,
            double nowSec,
            TaxiState taxi,
            Map<String, Integer> liveEdgeFlow
    ) {
        int hour = hourOfDay(nowSec);
        List<CongestedEdge> ranked = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String edgeId : visibleEdges) {
            if (edgeId == null || edgeId.isBlank() || !seen.add(edgeId)) {
                continue;
            }
            double base = edgeBaseCost(edgeId, hour);
            double operational = edgeOperationalCost(edgeId, hour, liveEdgeFlow, taxi, false);
            double extra = Math.max(0.0, operational - base);
            double ratio = operational / Math.max(1e-9, base);

            if (extra > 1e-9 || ratio > 1.0 + 1e-9) {
                ranked.add(new CongestedEdge(edgeId, extra, ratio));
            }
        }

        ranked.sort(
                Comparator.comparingDouble((CongestedEdge e) -> e.extraSec).reversed()
                        .thenComparing(Comparator.comparingDouble((CongestedEdge e) -> e.ratio).reversed())
        );
        return ranked;
    }

    /**
     * Bidirectional Dijkstra on a directed graph. The backward search walks the
     * reverse adjacency but evaluates the cost of the original directed edge.
     * Edge weights are fixed and non-negative during one reroute query.
     */
    private PathResult bidirectionalDijkstraAvoidingEdges(
            Map<String, List<GraphEdge>> graph,
            Map<String, List<GraphEdge>> reverseGraph,
            String startNode,
            String endNode,
            double nowSec,
            Map<String, Integer> liveEdgeFlow,
            TaxiState taxi,
            boolean useBaseOnly,
            Set<String> bannedEdgeIds
    ) {
        if (startNode == null || endNode == null || startNode.isBlank() || endNode.isBlank()) {
            return null;
        }
        if (startNode.equals(endNode)) {
            return new PathResult(List.of(startNode), List.of(), 0.0);
        }

        Map<String, Double> distF = new HashMap<>();
        Map<String, Double> distB = new HashMap<>();
        Map<String, String> prevNodeF = new HashMap<>();
        Map<String, String> prevEdgeF = new HashMap<>();
        Map<String, String> nextNodeB = new HashMap<>();
        Map<String, String> nextEdgeB = new HashMap<>();

        PriorityQueue<NodeState> pqF = new PriorityQueue<>(Comparator.comparingDouble(n -> n.cost));
        PriorityQueue<NodeState> pqB = new PriorityQueue<>(Comparator.comparingDouble(n -> n.cost));

        distF.put(startNode, 0.0);
        distB.put(endNode, 0.0);
        pqF.add(new NodeState(startNode, 0.0));
        pqB.add(new NodeState(endNode, 0.0));

        double bestCost = Double.POSITIVE_INFINITY;
        String meetingNode = null;
        int hour = hourOfDay(nowSec);

        while (true) {
            discardStaleQueueHeads(pqF, distF);
            discardStaleQueueHeads(pqB, distB);
            if (pqF.isEmpty() || pqB.isEmpty()) {
                break;
            }

            double minF = pqF.peek().cost;
            double minB = pqB.peek().cost;
            if (minF + minB >= bestCost) {
                break;
            }

            if (minF <= minB) {
                NodeState cur = pqF.poll();
                Double backwardAtCur = distB.get(cur.node);
                if (backwardAtCur != null && cur.cost + backwardAtCur < bestCost) {
                    bestCost = cur.cost + backwardAtCur;
                    meetingNode = cur.node;
                }

                for (GraphEdge edge : graph.getOrDefault(cur.node, List.of())) {
                    if (bannedEdgeIds.contains(edge.getEdgeId())) {
                        continue;
                    }
                    double w = edgeOperationalCost(edge.getEdgeId(), hour, liveEdgeFlow, taxi, useBaseOnly);
                    double nd = cur.cost + w;
                    String next = edge.getToNode();
                    Double old = distF.get(next);
                    if (old == null || nd < old) {
                        distF.put(next, nd);
                        prevNodeF.put(next, cur.node);
                        prevEdgeF.put(next, edge.getEdgeId());
                        pqF.add(new NodeState(next, nd));

                        Double other = distB.get(next);
                        if (other != null && nd + other < bestCost) {
                            bestCost = nd + other;
                            meetingNode = next;
                        }
                    }
                }
            } else {
                NodeState cur = pqB.poll();
                Double forwardAtCur = distF.get(cur.node);
                if (forwardAtCur != null && cur.cost + forwardAtCur < bestCost) {
                    bestCost = cur.cost + forwardAtCur;
                    meetingNode = cur.node;
                }

                // reverseGraph[cur] contains original incoming edges u -> cur.
                for (GraphEdge edge : reverseGraph.getOrDefault(cur.node, List.of())) {
                    if (bannedEdgeIds.contains(edge.getEdgeId())) {
                        continue;
                    }
                    double w = edgeOperationalCost(edge.getEdgeId(), hour, liveEdgeFlow, taxi, useBaseOnly);
                    double nd = cur.cost + w;
                    String prev = edge.getFromNode();
                    Double old = distB.get(prev);
                    if (old == null || nd < old) {
                        distB.put(prev, nd);
                        nextNodeB.put(prev, cur.node);
                        nextEdgeB.put(prev, edge.getEdgeId());
                        pqB.add(new NodeState(prev, nd));

                        Double other = distF.get(prev);
                        if (other != null && nd + other < bestCost) {
                            bestCost = nd + other;
                            meetingNode = prev;
                        }
                    }
                }
            }
        }

        if (meetingNode == null || !Double.isFinite(bestCost)) {
            return null;
        }

        List<String> nodesRev = new ArrayList<>();
        List<String> edgesRev = new ArrayList<>();
        String cursor = meetingNode;
        nodesRev.add(cursor);

        while (!cursor.equals(startNode)) {
            String pe = prevEdgeF.get(cursor);
            String pn = prevNodeF.get(cursor);
            if (pe == null || pn == null) {
                return null;
            }
            edgesRev.add(pe);
            nodesRev.add(pn);
            cursor = pn;
        }
        Collections.reverse(nodesRev);
        Collections.reverse(edgesRev);

        List<String> nodes = new ArrayList<>(nodesRev);
        List<String> edges = new ArrayList<>(edgesRev);
        cursor = meetingNode;
        while (!cursor.equals(endNode)) {
            String ne = nextEdgeB.get(cursor);
            String nn = nextNodeB.get(cursor);
            if (ne == null || nn == null) {
                return null;
            }
            edges.add(ne);
            nodes.add(nn);
            cursor = nn;
        }

        return new PathResult(nodes, edges, bestCost);
    }

    private static void discardStaleQueueHeads(
            PriorityQueue<NodeState> pq,
            Map<String, Double> dist
    ) {
        while (!pq.isEmpty()) {
            NodeState top = pq.peek();
            Double known = dist.get(top.node);
            if (known != null && top.cost <= known) {
                return;
            }
            pq.poll();
        }
    }

    private static String pathSignature(PathResult p) {
        return String.join("->", p.nodeSeq);
    }

    private static double edgeJaccard(List<String> aSeq, List<String> bSeq) {
        Set<String> a = new LinkedHashSet<>(aSeq);
        Set<String> b = new LinkedHashSet<>(bSeq);
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        Set<String> inter = new LinkedHashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        if (union.isEmpty()) {
            return 0.0;
        }
        return (double) inter.size() / (double) union.size();
    }


    private void maybeReroute(TaxiState taxi, double nowSec, SweepParameters params,
                              Map<String, Integer> liveEdgeFlow, Random rng) {
        maybeReroute(taxi, nowSec, params, liveEdgeFlow, rng, null);
    }

    private void maybeReroute(TaxiState taxi, double nowSec, SweepParameters params,
                              Map<String, Integer> liveEdgeFlow, Random rng,
                              RerouteDiagnostics rrDiag) {
        params = effectiveParams(taxi, params);
        if (rrDiag != null) {
            rrDiag.checks++;
        }

        if (taxi.rerouteCount >= params.maxReroutes) {
            if (rrDiag != null) {
                rrDiag.maxBlocked++;
            }
            return;
        }

        if (nowSec - taxi.lastRerouteDecisionTimeSec < params.rerouteCooldownSec) {
            if (rrDiag != null) {
                rrDiag.cooldownBlocked++;
            }
            return;
        }

        if (taxi.nextEdgeIndex >= taxi.plannedEdges.size()) {
            if (rrDiag != null) {
                rrDiag.finishedPathBlocked++;
            }
            return;
        }

        String currentNode = taxi.plannedNodes.get(taxi.nextEdgeIndex);
        String destNode = taxi.destinationNode;
        List<String> remainingCurrentEdges = taxi.plannedEdges.subList(
                taxi.nextEdgeIndex,
                taxi.plannedEdges.size()
        );

        if (remainingCurrentEdges.isEmpty()) {
            if (rrDiag != null) {
                rrDiag.emptyRemainingBlocked++;
            }
            return;
        }

        // Bounded-rational trigger: inspect only the configured look-ahead horizon.
        int lookAheadEnd = Math.min(
                remainingCurrentEdges.size(),
                Math.max(1, config.lookAheadEdges)
        );

        List<String> triggerEdges = remainingCurrentEdges.subList(0, lookAheadEnd);

        double referenceTriggerCost = estimatePathBaseCost(triggerEdges, nowSec);
        double currentTriggerCost = estimatePathCost(triggerEdges, nowSec, liveEdgeFlow, taxi, false);

        double routeRatio = currentTriggerCost / Math.max(1e-9, referenceTriggerCost);
        double routeExtraSec = Math.max(0.0, currentTriggerCost - referenceTriggerCost);

        if (rrDiag != null) {
            rrDiag.triggerEvaluated++;
            rrDiag.sumRouteRatio += routeRatio;
            rrDiag.sumRouteExtraSec += routeExtraSec;
            rrDiag.maxRouteRatio = Math.max(rrDiag.maxRouteRatio, routeRatio);
            rrDiag.maxRouteExtraSec = Math.max(rrDiag.maxRouteExtraSec, routeExtraSec);
        }

        debug("REROUTE_CHECK taxi=" + taxi.taxiId
                + " od=" + taxi.odId
                + " now=" + nowSec
                + " referenceTriggerCost=" + referenceTriggerCost
                + " currentTriggerCost=" + currentTriggerCost
                + " routeRatio=" + routeRatio
                + " routeExtraSec=" + routeExtraSec
                + " thresholdRatio=" + params.slowdownTriggerRatio
                + " thresholdSec=" + params.slowdownTriggerSec);

        boolean triggerPassed =
                routeRatio >= params.slowdownTriggerRatio ||
                        routeExtraSec >= params.slowdownTriggerSec;

        if (!triggerPassed) {
            if (rrDiag != null) {
                rrDiag.triggerFailed++;
            }
            return;
        }

        if (rrDiag != null) {
            rrDiag.triggered++;
        }

        /*
         * Important:
         * Cooldown applies once the taxi spends effort considering rerouting,
         * not only after a reroute is successfully applied. This prevents a failed
         * reroute search from being repeated again on the very next edge event.
         */
        taxi.lastRerouteDecisionTimeSec = nowSec;

        // Improvement is evaluated on the full remaining suffix.
        double currentRemainingCost = estimatePathCost(
                remainingCurrentEdges,
                nowSec,
                liveEdgeFlow,
                taxi,
                false
        );

        PathResult newSuffix = chooseBestRerouteSuffix(
                taxi,
                currentNode,
                destNode,
                remainingCurrentEdges,
                triggerEdges,
                nowSec,
                params,
                liveEdgeFlow,
                rng
        );

        debug("REROUTE_SUFFIX taxi=" + taxi.taxiId
                + " od=" + taxi.odId
                + " found=" + (newSuffix != null)
                + " edgeCount=" + ((newSuffix == null || newSuffix.edgeSeq == null)
                ? -1
                : newSuffix.edgeSeq.size()));

        if (newSuffix == null || newSuffix.edgeSeq == null || newSuffix.edgeSeq.isEmpty()) {
            if (rrDiag != null) {
                rrDiag.noCandidate++;
            }
            return;
        }

        double newCost = estimatePathCost(newSuffix.edgeSeq, nowSec, liveEdgeFlow, taxi, false);
        double improvementSec = currentRemainingCost - newCost;

        if (rrDiag != null) {
            rrDiag.gainEvaluated++;
            rrDiag.sumImprovementSec += improvementSec;
            rrDiag.maxImprovementSec = Math.max(rrDiag.maxImprovementSec, improvementSec);
        }

        debug("REROUTE_GAIN taxi=" + taxi.taxiId
                + " od=" + taxi.odId
                + " currentRemainingCost=" + currentRemainingCost
                + " newCost=" + newCost
                + " improvement=" + improvementSec
                + " threshold=" + params.rerouteGainThresholdSec);

        if (improvementSec < params.rerouteGainThresholdSec) {
            if (rrDiag != null) {
                rrDiag.gainFailed++;
            }
            return;
        }

        List<String> newNodes = new ArrayList<>(
                taxi.plannedNodes.subList(0, taxi.nextEdgeIndex + 1)
        );

        List<String> newEdgesPrefix = new ArrayList<>(
                taxi.plannedEdges.subList(0, taxi.nextEdgeIndex)
        );

        for (int i = 1; i < newSuffix.nodeSeq.size(); i++) {
            newNodes.add(newSuffix.nodeSeq.get(i));
        }

        newEdgesPrefix.addAll(newSuffix.edgeSeq);

        taxi.plannedNodes = newNodes;
        taxi.plannedEdges = newEdgesPrefix;
        taxi.finalRouteId = taxi.initialRouteId + "|rerouted_" + (taxi.rerouteCount + 1);
        taxi.rerouteCount++;
        taxi.lastRerouteDecisionTimeSec = nowSec;


        if (rrDiag != null) {
            rrDiag.applied++;
        }

        debug("REROUTE taxi=" + taxi.taxiId
                + " od=" + taxi.odId
                + " time=" + nowSec
                + " referenceTriggerCost=" + referenceTriggerCost
                + " currentRemainingCost=" + currentRemainingCost
                + " newCost=" + newCost
                + " improvement=" + improvementSec
                + " rerouteCount=" + taxi.rerouteCount);
    }


    private void debug(String message) {
        if (config.debugRerouting) {
            System.out.println(message);
        }
    }

    private void scheduleNextEdgeEntry(TaxiState taxi, double nowSec, Map<String, Integer> liveEdgeFlow) {
        if (taxi.nextEdgeIndex >= taxi.plannedEdges.size()) {
            taxi.nextEventTimeSec = Double.POSITIVE_INFINITY;
            taxi.traversingEdgeId = "";
            return;
        }
        String edgeId = taxi.plannedEdges.get(taxi.nextEdgeIndex);
        incrementFlow(liveEdgeFlow, edgeId);
        taxi.traversingEdgeId = edgeId;
        double travelSec = edgeOperationalCost(edgeId, hourOfDay(nowSec), liveEdgeFlow, taxi,
                taxi.mode == TaxiState.Mode.REPOSITIONING && config.repositionUsesBaseCost);
        taxi.nextEventTimeSec = nowSec + travelSec;
    }

    private void updateMemoryFromTrip(TaxiState taxi, Map<String, Integer> liveEdgeFlow) {
        if (taxi.realizedEdges.isEmpty()) {
            return;
        }
        double elapsedBeforeEdge = 0.0;
        for (int i = 0; i < taxi.realizedEdges.size(); i++) {
            String edgeId = taxi.realizedEdges.get(i);
            double actualEdgeSec = i < taxi.realizedEdgeTravelSec.size()
                    ? taxi.realizedEdgeTravelSec.get(i)
                    : 0.0;
            double edgeStartTimeSec = taxi.serviceStartTimeSec + elapsedBeforeEdge;
            double baseEdgeSec = edgeBaseCost(edgeId, hourOfDay(edgeStartTimeSec));
            double penaltySec = Math.max(0.0, actualEdgeSec - baseEdgeSec);
            taxi.updateEdgeMemory(edgeId, penaltySec, effectiveMemoryLearningRate(taxi));
            elapsedBeforeEdge += actualEdgeSec;
        }
    }

    private TripOutcome buildOutcome(TaxiState taxi) {
        JaccardEvaluator.SequenceMatchResult match = JaccardEvaluator.bestSequenceMatch(
                taxi.realizedEdges,
                repo.getStableRoutesByOd().getOrDefault(taxi.odId, List.of()),
                config.routeMatchShortlistTopK,
                config.routeMatchShortlistMinJaccard,
                config.routeMatchNlcsWeight,
                config.routeMatchBigramWeight,
                config.routeMatchMinScore,
                config.routeMatchMinMargin
        );
        return new TripOutcome(
                String.format(Locale.US, "sim_taxi_%d_trip_%d", taxi.taxiId, taxi.completedTrips),
                taxi.taxiId,
                taxi.getTaxiClassId(),
                taxi.completedTrips,
                taxi.odId,
                taxi.initialRouteId,
                taxi.finalRouteId,
                taxi.rerouteCount,
                taxi.requestedStartTimeSec,
                taxi.serviceStartTimeSec,
                taxi.stateStartTimeSec,
                taxi.expectedTripTimeSec,
                taxi.accumulatedTripTimeSec,
                taxi.realizedEdges,
                taxi.realizedEdgeTravelSec,
                taxi.realizedNodes,
                match.bestSetJaccard,
                match.bestScore,
                match.secondBestScore,
                match.margin,
                match.status,
                match.nearestRouteId,
                match.assignedRouteId
        );
    }

    /** Dynamic network travel time without taxi-specific memory. */
    private double estimatePathTrafficCost(List<String> edgeSeq, double nowSec,
                                           Map<String, Integer> liveEdgeFlow) {
        double sum = 0.0;
        int hour = hourOfDay(nowSec);
        for (String edgeId : edgeSeq) {
            sum += edgeTrafficCost(edgeId, hour, liveEdgeFlow);
        }
        return sum;
    }

    private double estimatePathCost(List<String> edgeSeq, double nowSec, Map<String, Integer> liveEdgeFlow,
                                    TaxiState taxi, boolean useBaseOnly) {
        double sum = 0.0;
        int hour = hourOfDay(nowSec);
        for (String edgeId : edgeSeq) {
            sum += edgeOperationalCost(edgeId, hour, liveEdgeFlow, taxi, useBaseOnly);
        }
        return sum;
    }

    private double estimatePathBaseCost(List<String> edgeSeq, double nowSec) {
        double sum = 0.0;
        int hour = hourOfDay(nowSec);
        for (String edgeId : edgeSeq) {
            sum += edgeBaseCost(edgeId, hour);
        }
        return sum;
    }

    private double edgeBaseCost(String edgeId, int hour) {
        EdgeStats stats = repo.getEdgeStatsById().get(edgeId);
        if (stats == null) {
            return 30.0;
        }
        double base = stats.getBaseCostSec(hour);
        if (!Double.isFinite(base) || base <= 0.0) {
            return 30.0;
        }
        if (base > 3600.0) {
            System.out.println("WARN huge base edge cost: edgeId=" + edgeId + " hour=" + hour + " base=" + base);
        }
        return Math.max(1.0, base);
    }

    /**
     * Physical network travel time. Taxi-specific memory is deliberately not
     * included here: memory is a subjective route-choice feature, not a force
     * that changes how fast the vehicle physically traverses an edge.
     */
    private double edgeTrafficCost(String edgeId, int hour, Map<String, Integer> liveEdgeFlow) {
        double base = edgeBaseCost(edgeId, hour);
        EdgeStats stats = repo.getEdgeStatsById().get(edgeId);

        SweepParameters physics = activeRunParams;
        double alpha = physics == null ? 0.5 : physics.bprAlpha;
        double beta = physics == null ? 2.0 : physics.bprBeta;
        double capacityScale = physics == null ? 1.0 : physics.capacityScale;
        double backgroundScale = physics == null ? config.backgroundTrafficScale : physics.backgroundTrafficScale;

        double liveFlow = liveEdgeFlow.getOrDefault(edgeId, 0);
        double backgroundFlow = backgroundScale * repo.getBackgroundTrafficFlow(edgeId, hour);
        double flow = liveFlow + backgroundFlow;

        // Preserve the existing traversal-count capacity proxy as the nominal
        // capacity, then expose a multiplicative calibration factor.
        double nominalCap = stats == null
                ? 5.0
                : Math.max(3.0, Math.min(20.0, stats.getTraversalCount() / 20.0));
        double cap = Math.max(1e-9, nominalCap * capacityScale);
        double x = Math.max(0.0, flow / cap);
        double bprMultiplier = 1.0 + alpha * Math.pow(x, beta);
        return base * bprMultiplier;
    }

    /**
     * Operational cost used by physical traversal, route-time estimation and
     * shortest-path search. Memory is handled separately by betaMemory.
     */
    private double edgeOperationalCost(String edgeId, int hour, Map<String, Integer> liveEdgeFlow,
                                       TaxiState taxi, boolean useBaseOnly) {
        if (useBaseOnly) {
            return edgeBaseCost(edgeId, hour);
        }
        return edgeTrafficCost(edgeId, hour, liveEdgeFlow);
    }

    private static void incrementFlow(Map<String, Integer> flow, String edgeId) {
        flow.put(edgeId, flow.getOrDefault(edgeId, 0) + 1);
    }

    private static void decrementFlow(Map<String, Integer> flow, String edgeId) {
        int current = flow.getOrDefault(edgeId, 0);
        if (current <= 1) {
            flow.remove(edgeId);
        } else {
            flow.put(edgeId, current - 1);
        }
    }

    private static int hourOfDay(double timeSec) {
        int hour = (int) Math.floor(timeSec / 3600.0) % 24;
        if (hour < 0) {
            hour += 24;
        }
        return hour;
    }

    private PathResult dijkstra(Map<String, List<GraphEdge>> graph, String startNode, String endNode,
                                double nowSec, Map<String, Integer> liveEdgeFlow, TaxiState taxi,
                                boolean useBaseOnly, List<String> currentSuffix) {
        if (startNode == null || endNode == null || startNode.isBlank() || endNode.isBlank()) {
            return null;
        }
        if (startNode.equals(endNode)) {
            List<String> nodes = new ArrayList<>();
            nodes.add(startNode);
            return new PathResult(nodes, new ArrayList<>(), 0.0);
        }

        Map<String, Double> dist = new HashMap<>();
        Map<String, String> prevNode = new HashMap<>();
        Map<String, String> prevEdge = new HashMap<>();
        PriorityQueue<NodeState> pq = new PriorityQueue<>(Comparator.comparingDouble(n -> n.cost));
        dist.put(startNode, 0.0);
        pq.add(new NodeState(startNode, 0.0));

        while (!pq.isEmpty()) {
            NodeState cur = pq.poll();
            if (cur.cost > dist.getOrDefault(cur.node, Double.POSITIVE_INFINITY)) {
                continue;
            }
            if (cur.node.equals(endNode)) {
                break;
            }
            for (GraphEdge edge : graph.getOrDefault(cur.node, List.of())) {
                double w = edgeOperationalCost(edge.getEdgeId(), hourOfDay(nowSec), liveEdgeFlow, taxi, useBaseOnly);
                double nd = cur.cost + w;
                if (nd < dist.getOrDefault(edge.getToNode(), Double.POSITIVE_INFINITY)) {
                    dist.put(edge.getToNode(), nd);
                    prevNode.put(edge.getToNode(), cur.node);
                    prevEdge.put(edge.getToNode(), edge.getEdgeId());
                    pq.add(new NodeState(edge.getToNode(), nd));
                }
            }
        }

        if (!dist.containsKey(endNode)) {
            return null;
        }

        List<String> nodesRev = new ArrayList<>();
        List<String> edgesRev = new ArrayList<>();
        String cursor = endNode;
        nodesRev.add(cursor);
        while (!cursor.equals(startNode)) {
            String pe = prevEdge.get(cursor);
            String pn = prevNode.get(cursor);
            if (pe == null || pn == null) {
                return null;
            }
            edgesRev.add(pe);
            nodesRev.add(pn);
            cursor = pn;
        }
        java.util.Collections.reverse(nodesRev);
        java.util.Collections.reverse(edgesRev);

        if (currentSuffix != null && !currentSuffix.isEmpty() && edgesRev.equals(currentSuffix)) {
            return null;
        }
        return new PathResult(nodesRev, edgesRev, dist.get(endNode));
    }

    private record NodeState(String node, double cost) {
    }

    private record PathResult(List<String> nodeSeq, List<String> edgeSeq, double costSec) {
    }
}
