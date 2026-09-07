package porto.sweep.sim;

import porto.sweep.config.SweepConfig;
import porto.sweep.eval.JaccardEvaluator;
import porto.sweep.io.DataRepository;
import porto.sweep.model.StableRouteRef;
import porto.sweep.model.TripOutcome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Streaming accumulator for the configurable sweep objective. It keeps only
 * aggregate counters, so it is suitable for multi-million-trip simulations.
 */
final class ObjectiveMetricsAccumulator {
    private final DataRepository repo;
    private final SweepConfig config;

    private final Map<String, Integer> completedByOd = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> simulatedRouteCountsByOd = new LinkedHashMap<>();
    private final Map<String, Long> simulatedEdgeCounts = new LinkedHashMap<>();
    private final Map<String, Double> actualTimeSumByOd = new LinkedHashMap<>();

    private int completedTrips;
    private int matchedTrips;
    private int unmatchedTrips;
    private int ambiguousTrips;
    private int noReferenceTrips;
    private double sumRouteMatchScore;

    ObjectiveMetricsAccumulator(DataRepository repo, SweepConfig config) {
        this.repo = repo;
        this.config = config;
    }

    void add(TripOutcome outcome) {
        completedTrips++;
        String odId = outcome.getOdId() == null ? "" : outcome.getOdId();
        completedByOd.merge(odId, 1, Integer::sum);
        actualTimeSumByOd.merge(odId, outcome.getActualTimeSec(), Double::sum);
        sumRouteMatchScore += outcome.getRouteMatchScore();

        String status = outcome.getRouteMatchStatus();
        String category = outcome.getMatchedRealRouteId();
        if (JaccardEvaluator.MATCHED.equals(status)) {
            matchedTrips++;
        } else {
            category = JaccardEvaluator.UNMATCHED_ROUTE_ID;
            unmatchedTrips++;
            if (JaccardEvaluator.AMBIGUOUS.equals(status)) {
                ambiguousTrips++;
            } else if (JaccardEvaluator.NO_REFERENCE.equals(status)) {
                noReferenceTrips++;
            }
        }
        simulatedRouteCountsByOd
                .computeIfAbsent(odId, k -> new LinkedHashMap<>())
                .merge(category, 1, Integer::sum);

        for (String edgeId : outcome.getRealizedEdgeSeq()) {
            if (edgeId != null && !edgeId.isBlank()) {
                simulatedEdgeCounts.merge(edgeId, 1L, Long::sum);
            }
        }
    }

    ObjectiveMetrics finish(double completionRate) {
        double finalRouteShareJsd = computeWeightedRouteShareJsd();
        double finalRouteShareMae = computeWeightedRouteShareMae();
        double edgeFlowWmae = computeEdgeFlowWmae();
        TravelTimeMetric travelMetric = computeTravelTimeWmae();
        double simulatedNoveltyRate = completedTrips == 0 ? 0.0 : unmatchedTrips / (double) completedTrips;
        double observedNoveltyRate = computeWeightedObservedNoveltyRate();
        double noveltyRateError = Math.abs(simulatedNoveltyRate - observedNoveltyRate);

        double normalizedFinal = safeNormalize(finalRouteShareJsd, config.objectiveScaleFinalRouteShare);
        double normalizedEdge = safeNormalize(edgeFlowWmae, config.objectiveScaleEdgeFlow);
        double normalizedTravel = safeNormalize(travelMetric.errorSec, config.objectiveScaleTravelTimeSec);
        double normalizedNovelty = safeNormalize(noveltyRateError, config.objectiveScaleNovelty);

        double weightSum = config.objectiveWeightFinalRouteShare
                + config.objectiveWeightEdgeFlow
                + config.objectiveWeightTravelTime
                + config.objectiveWeightNovelty;
        double objectiveLoss = (
                weightedTerm(config.objectiveWeightFinalRouteShare, normalizedFinal)
                        + weightedTerm(config.objectiveWeightEdgeFlow, normalizedEdge)
                        + weightedTerm(config.objectiveWeightTravelTime, normalizedTravel)
                        + weightedTerm(config.objectiveWeightNovelty, normalizedNovelty)
        ) / weightSum;

        boolean eligible = !config.objectiveEnabled
                || (completionRate >= config.objectiveMinimumCompletionRate
                && Double.isFinite(objectiveLoss));

        return new ObjectiveMetrics(
                finalRouteShareJsd,
                finalRouteShareMae,
                edgeFlowWmae,
                travelMetric.errorSec,
                simulatedNoveltyRate,
                observedNoveltyRate,
                noveltyRateError,
                normalizedFinal,
                normalizedEdge,
                normalizedTravel,
                normalizedNovelty,
                objectiveLoss,
                eligible,
                completedTrips == 0 ? 0.0 : sumRouteMatchScore / completedTrips,
                matchedTrips,
                unmatchedTrips,
                ambiguousTrips,
                noReferenceTrips,
                travelMetric.comparableTrips
        );
    }

    private double computeWeightedRouteShareJsd() {
        double weighted = 0.0;
        int denominator = 0;
        for (Map.Entry<String, Integer> entry : completedByOd.entrySet()) {
            String odId = entry.getKey();
            int n = entry.getValue();
            if (n <= 0) {
                continue;
            }
            Map<String, Double> observed = observedRouteDistribution(odId);
            Map<String, Double> simulated = simulatedRouteDistribution(odId, n);
            weighted += n * jensenShannon(observed, simulated);
            denominator += n;
        }
        return denominator == 0 ? 0.0 : weighted / denominator;
    }

    private double computeWeightedRouteShareMae() {
        double weighted = 0.0;
        int denominator = 0;
        for (Map.Entry<String, Integer> entry : completedByOd.entrySet()) {
            String odId = entry.getKey();
            int n = entry.getValue();
            if (n <= 0) {
                continue;
            }
            Map<String, Double> observed = observedRouteDistribution(odId);
            Map<String, Double> simulated = simulatedRouteDistribution(odId, n);
            Set<String> categories = new LinkedHashSet<>();
            categories.addAll(observed.keySet());
            categories.addAll(simulated.keySet());
            double sumAbs = 0.0;
            for (String category : categories) {
                sumAbs += Math.abs(simulated.getOrDefault(category, 0.0)
                        - observed.getOrDefault(category, 0.0));
            }
            double mae = categories.isEmpty() ? 0.0 : sumAbs / categories.size();
            weighted += n * mae;
            denominator += n;
        }
        return denominator == 0 ? 0.0 : weighted / denominator;
    }

    private Map<String, Double> simulatedRouteDistribution(String odId, int n) {
        Map<String, Double> out = new LinkedHashMap<>();
        Map<String, Integer> counts = simulatedRouteCountsByOd.getOrDefault(odId, Map.of());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            out.put(entry.getKey(), entry.getValue() / (double) n);
        }
        out.putIfAbsent(JaccardEvaluator.UNMATCHED_ROUTE_ID, 0.0);
        return out;
    }

    private Map<String, Double> observedRouteDistribution(String odId) {
        List<StableRouteRef> refs = repo.getStableRoutesByOd().getOrDefault(odId, List.of());
        Map<String, Double> out = new LinkedHashMap<>();
        double rawSum = 0.0;
        for (StableRouteRef ref : refs) {
            double share = validShare(ref.getStableShare());
            rawSum += share;
        }

        double novelty = observedNoveltyForOd(rawSum, refs.isEmpty());
        double stableMass = Math.max(0.0, 1.0 - novelty);
        if (rawSum > 0.0) {
            for (StableRouteRef ref : refs) {
                double share = validShare(ref.getStableShare());
                out.merge(ref.getRouteId(), stableMass * share / rawSum, Double::sum);
            }
        } else if (!refs.isEmpty()) {
            double equal = stableMass / refs.size();
            for (StableRouteRef ref : refs) {
                out.merge(ref.getRouteId(), equal, Double::sum);
            }
        } else {
            novelty = 1.0;
        }
        out.put(JaccardEvaluator.UNMATCHED_ROUTE_ID, novelty);
        return out;
    }

    private double observedNoveltyForOd(double rawStableShareSum, boolean noReferences) {
        if (noReferences) {
            return 1.0;
        }
        if (config.objectiveObservedNoveltyRate >= 0.0) {
            return clamp01(config.objectiveObservedNoveltyRate);
        }
        return clamp01(1.0 - rawStableShareSum);
    }

    private double computeWeightedObservedNoveltyRate() {
        if (completedTrips == 0) {
            return 0.0;
        }
        double weighted = 0.0;
        for (Map.Entry<String, Integer> entry : completedByOd.entrySet()) {
            List<StableRouteRef> refs = repo.getStableRoutesByOd().getOrDefault(entry.getKey(), List.of());
            double rawSum = 0.0;
            for (StableRouteRef ref : refs) {
                rawSum += validShare(ref.getStableShare());
            }
            weighted += entry.getValue() * observedNoveltyForOd(rawSum, refs.isEmpty());
        }
        return weighted / completedTrips;
    }

    /**
     * Observed edge usage is reconstructed from fixed stable-route shares for
     * the exact OD mix completed by this run. The error is the observed-flow
     * weighted MAE of relative edge-count errors, algebraically equal to
     * sum(|sim-target|) / sum(target).
     */
    private double computeEdgeFlowWmae() {
        Map<String, Double> targetEdgeCounts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : completedByOd.entrySet()) {
            String odId = entry.getKey();
            int trips = entry.getValue();
            Map<String, Double> expectedPerTrip = expectedStableEdgeUsagePerTrip(odId);
            for (Map.Entry<String, Double> edge : expectedPerTrip.entrySet()) {
                targetEdgeCounts.merge(edge.getKey(), trips * edge.getValue(), Double::sum);
            }
        }

        Set<String> edges = new HashSet<>();
        edges.addAll(targetEdgeCounts.keySet());
        edges.addAll(simulatedEdgeCounts.keySet());
        double absError = 0.0;
        double targetTotal = 0.0;
        for (String edgeId : edges) {
            double target = targetEdgeCounts.getOrDefault(edgeId, 0.0);
            double simulated = simulatedEdgeCounts.getOrDefault(edgeId, 0L);
            absError += Math.abs(simulated - target);
            targetTotal += target;
        }
        return targetTotal <= 1e-12 ? Double.NaN : absError / targetTotal;
    }

    private Map<String, Double> expectedStableEdgeUsagePerTrip(String odId) {
        List<StableRouteRef> refs = repo.getStableRoutesByOd().getOrDefault(odId, List.of());
        Map<String, Double> out = new LinkedHashMap<>();
        double rawSum = 0.0;
        for (StableRouteRef ref : refs) {
            rawSum += validShare(ref.getStableShare());
        }
        if (refs.isEmpty()) {
            return out;
        }
        for (StableRouteRef ref : refs) {
            double probability = rawSum > 0.0
                    ? validShare(ref.getStableShare()) / rawSum
                    : 1.0 / refs.size();
            for (String edgeId : ref.getEdgeSeq()) {
                if (edgeId != null && !edgeId.isBlank()) {
                    out.merge(edgeId, probability, Double::sum);
                }
            }
        }
        return out;
    }

    private TravelTimeMetric computeTravelTimeWmae() {
        double weightedAbsError = 0.0;
        int comparableTrips = 0;
        for (Map.Entry<String, Integer> entry : completedByOd.entrySet()) {
            String odId = entry.getKey();
            int n = entry.getValue();
            if (n <= 0) {
                continue;
            }
            double target = observedTravelTimeForOd(odId);
            if (!Double.isFinite(target) || target <= 0.0) {
                continue;
            }
            double simulatedMean = actualTimeSumByOd.getOrDefault(odId, 0.0) / n;
            weightedAbsError += n * Math.abs(simulatedMean - target);
            comparableTrips += n;
        }
        return new TravelTimeMetric(
                comparableTrips == 0 ? Double.NaN : weightedAbsError / comparableTrips,
                comparableTrips
        );
    }

    /**
     * Observed OD travel-time target derived only from the observed stable-route
     * table. avg_time_sec is produced from realized observed edge travel times
     * during preprocessing; candidate-route / graph travel times are never used
     * here. Routes without a valid observed avg_time_sec are omitted and the
     * remaining stable shares are renormalized. If an OD has no valid observed
     * travel-time reference, it is excluded from travel-time WMAE.
     */
    private double observedTravelTimeForOd(String odId) {
        List<StableRouteRef> refs = repo.getStableRoutesByOd().getOrDefault(odId, List.of());
        if (refs.isEmpty()) {
            return Double.NaN;
        }

        double weighted = 0.0;
        double weightSum = 0.0;
        int validCount = 0;
        double unweightedSum = 0.0;

        for (StableRouteRef ref : refs) {
            double observedTimeSec = ref.getAvgTimeSec();
            if (!Double.isFinite(observedTimeSec) || observedTimeSec <= 0.0) {
                continue;
            }

            validCount++;
            unweightedSum += observedTimeSec;

            double weight = validShare(ref.getStableShare());
            if (weight > 0.0) {
                weighted += weight * observedTimeSec;
                weightSum += weight;
            }
        }

        if (weightSum > 0.0) {
            return weighted / weightSum;
        }

        // Stable shares should normally be available. If not, use an equal
        // average across valid observed stable routes rather than falling back
        // to graph-generated candidate travel times.
        return validCount == 0 ? Double.NaN : unweightedSum / validCount;
    }

    private static double jensenShannon(Map<String, Double> p, Map<String, Double> q) {
        Set<String> categories = new LinkedHashSet<>();
        categories.addAll(p.keySet());
        categories.addAll(q.keySet());
        double divergence = 0.0;
        for (String category : categories) {
            double pv = Math.max(0.0, p.getOrDefault(category, 0.0));
            double qv = Math.max(0.0, q.getOrDefault(category, 0.0));
            double m = 0.5 * (pv + qv);
            if (pv > 0.0 && m > 0.0) {
                divergence += 0.5 * pv * log2(pv / m);
            }
            if (qv > 0.0 && m > 0.0) {
                divergence += 0.5 * qv * log2(qv / m);
            }
        }
        // Floating-point noise can create tiny negative values.
        return Math.max(0.0, divergence);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    private static double weightedTerm(double weight, double normalizedError) {
        if (weight == 0.0) {
            return 0.0;
        }
        return weight * normalizedError;
    }

    private static double safeNormalize(double error, double scale) {
        if (!Double.isFinite(error)) {
            return Double.POSITIVE_INFINITY;
        }
        return error / scale;
    }

    private static double validShare(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    static final class ObjectiveMetrics {
        final double finalRouteShareJsd;
        final double finalRouteShareMae;
        final double edgeFlowWmae;
        final double travelTimeWmaeSec;
        final double simulatedNoveltyRate;
        final double observedNoveltyRate;
        final double noveltyRateError;
        final double normalizedFinalRouteShareError;
        final double normalizedEdgeFlowError;
        final double normalizedTravelTimeError;
        final double normalizedNoveltyError;
        final double objectiveLoss;
        final boolean objectiveEligible;
        final double meanRouteMatchScore;
        final int matchedTripCount;
        final int unmatchedTripCount;
        final int ambiguousTripCount;
        final int noReferenceTripCount;
        final int travelTimeComparableTripCount;

        ObjectiveMetrics(double finalRouteShareJsd, double finalRouteShareMae,
                         double edgeFlowWmae, double travelTimeWmaeSec,
                         double simulatedNoveltyRate, double observedNoveltyRate,
                         double noveltyRateError, double normalizedFinalRouteShareError,
                         double normalizedEdgeFlowError, double normalizedTravelTimeError,
                         double normalizedNoveltyError, double objectiveLoss,
                         boolean objectiveEligible, double meanRouteMatchScore,
                         int matchedTripCount, int unmatchedTripCount,
                         int ambiguousTripCount, int noReferenceTripCount,
                         int travelTimeComparableTripCount) {
            this.finalRouteShareJsd = finalRouteShareJsd;
            this.finalRouteShareMae = finalRouteShareMae;
            this.edgeFlowWmae = edgeFlowWmae;
            this.travelTimeWmaeSec = travelTimeWmaeSec;
            this.simulatedNoveltyRate = simulatedNoveltyRate;
            this.observedNoveltyRate = observedNoveltyRate;
            this.noveltyRateError = noveltyRateError;
            this.normalizedFinalRouteShareError = normalizedFinalRouteShareError;
            this.normalizedEdgeFlowError = normalizedEdgeFlowError;
            this.normalizedTravelTimeError = normalizedTravelTimeError;
            this.normalizedNoveltyError = normalizedNoveltyError;
            this.objectiveLoss = objectiveLoss;
            this.objectiveEligible = objectiveEligible;
            this.meanRouteMatchScore = meanRouteMatchScore;
            this.matchedTripCount = matchedTripCount;
            this.unmatchedTripCount = unmatchedTripCount;
            this.ambiguousTripCount = ambiguousTripCount;
            this.noReferenceTripCount = noReferenceTripCount;
            this.travelTimeComparableTripCount = travelTimeComparableTripCount;
        }
    }

    private static final class TravelTimeMetric {
        final double errorSec;
        final int comparableTrips;

        TravelTimeMetric(double errorSec, int comparableTrips) {
            this.errorSec = errorSec;
            this.comparableTrips = comparableTrips;
        }
    }
}
