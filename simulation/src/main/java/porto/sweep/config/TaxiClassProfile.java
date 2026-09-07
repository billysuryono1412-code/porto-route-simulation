package porto.sweep.config;

import porto.sweep.io.SimpleCsv;
import porto.sweep.sim.SweepParameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Optional persistent behavioural profile assigned to a taxi. */
public final class TaxiClassProfile {
    public final String classId;
    public final double share;

    private final double betaTime;
    private final double betaMemory;
    private final double betaEdgeHabit;
    private final double betaDetour;
    private final double betaComplexity;
    private final double decisionNoiseStd;
    private final double slowdownTriggerRatio;
    private final double slowdownTriggerSec;
    private final double rerouteGainThresholdSec;
    private final double rerouteCooldownSec;
    private final Integer maxReroutes;
    private final double memoryLearningRate;

    private TaxiClassProfile(String classId, double share,
                             double betaTime, double betaMemory, double betaEdgeHabit,
                             double betaDetour, double betaComplexity, double decisionNoiseStd,
                             double slowdownTriggerRatio, double slowdownTriggerSec,
                             double rerouteGainThresholdSec, double rerouteCooldownSec,
                             Integer maxReroutes, double memoryLearningRate) {
        this.classId = classId;
        this.share = share;
        this.betaTime = betaTime;
        this.betaMemory = betaMemory;
        this.betaEdgeHabit = betaEdgeHabit;
        this.betaDetour = betaDetour;
        this.betaComplexity = betaComplexity;
        this.decisionNoiseStd = decisionNoiseStd;
        this.slowdownTriggerRatio = slowdownTriggerRatio;
        this.slowdownTriggerSec = slowdownTriggerSec;
        this.rerouteGainThresholdSec = rerouteGainThresholdSec;
        this.rerouteCooldownSec = rerouteCooldownSec;
        this.maxReroutes = maxReroutes;
        this.memoryLearningRate = memoryLearningRate;
    }

    public SweepParameters resolve(SweepParameters fallback) {
        return new SweepParameters(
                choose(betaTime, fallback.betaTime),
                choose(betaMemory, fallback.betaMemory),
                choose(betaEdgeHabit, fallback.betaEdgeHabit),
                choose(betaDetour, fallback.betaDetour),
                choose(betaComplexity, fallback.betaComplexity),
                choose(decisionNoiseStd, fallback.decisionNoiseStd),
                choose(slowdownTriggerRatio, fallback.slowdownTriggerRatio),
                choose(slowdownTriggerSec, fallback.slowdownTriggerSec),
                choose(rerouteGainThresholdSec, fallback.rerouteGainThresholdSec),
                choose(rerouteCooldownSec, fallback.rerouteCooldownSec),
                maxReroutes == null ? fallback.maxReroutes : maxReroutes,
                fallback.bprAlpha,
                fallback.bprBeta,
                fallback.capacityScale,
                fallback.backgroundTrafficScale,
                fallback.routeChoiceMode
        );
    }

    public double resolveMemoryLearningRate(double fallback) {
        return choose(memoryLearningRate, fallback);
    }

    public static List<TaxiClassProfile> load(Path path) throws IOException {
        List<TaxiClassProfile> out = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (Map<String, String> row : SimpleCsv.read(path)) {
            String classId = firstNonBlank(row, "taxi_class", "class_id", "class", "name");
            if (classId.isBlank()) continue;
            if (!seenIds.add(classId)) throw new IllegalArgumentException("Duplicate taxi class ID: " + classId);

            double share = parseRequiredPositive(firstNonBlank(row, "share", "proportion", "weight"),
                    "share for taxi class " + classId);
            out.add(new TaxiClassProfile(
                    classId,
                    share,
                    parseOptionalDouble(firstNonBlank(row, "betaTime", "beta_time")),
                    parseOptionalDouble(firstNonBlank(row, "betaMemory", "beta_memory")),
                    parseOptionalDouble(firstNonBlank(row, "betaEdgeHabit", "beta_edge_habit")),
                    parseOptionalDouble(firstNonBlank(row, "betaDetour", "beta_detour")),
                    parseOptionalDouble(firstNonBlank(row, "betaComplexity", "beta_complexity")),
                    parseOptionalNonNegative(firstNonBlank(row, "decisionNoiseStd", "decision_noise_std"),
                            "decisionNoiseStd for taxi class " + classId),
                    parseOptionalPositive(firstNonBlank(row, "slowdownTriggerRatio", "slowdown_trigger_ratio"),
                            "slowdownTriggerRatio for taxi class " + classId),
                    parseOptionalNonNegative(firstNonBlank(row, "slowdownTriggerSec", "slowdown_trigger_sec"),
                            "slowdownTriggerSec for taxi class " + classId),
                    parseOptionalNonNegative(firstNonBlank(row, "rerouteGainThresholdSec", "reroute_gain_threshold_sec"),
                            "rerouteGainThresholdSec for taxi class " + classId),
                    parseOptionalNonNegative(firstNonBlank(row, "rerouteCooldownSec", "reroute_cooldown_sec"),
                            "rerouteCooldownSec for taxi class " + classId),
                    parseOptionalNonNegativeInt(firstNonBlank(row, "maxReroutes", "max_reroutes"),
                            "maxReroutes for taxi class " + classId),
                    parseOptionalRate(firstNonBlank(row, "memoryLearningRate", "memory_learning_rate"),
                            "memoryLearningRate for taxi class " + classId)
            ));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("No valid taxi classes found in: " + path);
        return out;
    }

    private static double choose(double override, double fallback) { return Double.isFinite(override) ? override : fallback; }
    private static String firstNonBlank(Map<String, String> row, String... keys) {
        for (String key : keys) {
            String value = row.get(key);
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }
    private static double parseOptionalDouble(String raw) {
        if (raw == null || raw.isBlank()) return Double.NaN;
        double value = Double.parseDouble(raw.trim());
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Taxi-class parameter must be finite: " + raw);
        return value;
    }
    private static double parseRequiredPositive(String raw, String label) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Missing " + label);
        double value = Double.parseDouble(raw.trim());
        if (!Double.isFinite(value) || value <= 0.0) throw new IllegalArgumentException(label + " must be positive.");
        return value;
    }
    private static double parseOptionalPositive(String raw, String label) {
        if (raw == null || raw.isBlank()) return Double.NaN;
        double value = Double.parseDouble(raw.trim());
        if (!Double.isFinite(value) || value <= 0.0) throw new IllegalArgumentException(label + " must be positive.");
        return value;
    }
    private static double parseOptionalNonNegative(String raw, String label) {
        if (raw == null || raw.isBlank()) return Double.NaN;
        double value = Double.parseDouble(raw.trim());
        if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException(label + " must be non-negative.");
        return value;
    }
    private static double parseOptionalRate(String raw, String label) {
        if (raw == null || raw.isBlank()) return Double.NaN;
        double value = Double.parseDouble(raw.trim());
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) throw new IllegalArgumentException(label + " must be between 0 and 1.");
        return value;
    }
    private static Integer parseOptionalNonNegativeInt(String raw, String label) {
        if (raw == null || raw.isBlank()) return null;
        int value = Integer.parseInt(raw.trim());
        if (value < 0) throw new IllegalArgumentException(label + " must be non-negative.");
        return value;
    }
}
