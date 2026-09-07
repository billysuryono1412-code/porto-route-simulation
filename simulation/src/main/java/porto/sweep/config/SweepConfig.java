package porto.sweep.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class SweepConfig {
    public Path candidateRoutes;
    public Path routeWaypoints;
    public Path edgeLookup;
    public Path stableRoutes;
    public Path outputDir;
    public Path rerouteTimeGraphCsv;
    public Path rerouteLengthGraphCsv;
    public Path backgroundTrafficCsv;
    public double backgroundTrafficScale;
    public Path taxiClassesCsv;
    public List<TaxiClassProfile> taxiClasses;
    public int topKOutputs;

    public long seed;
    public int nTaxis;
    public int totalTrips;
    public int maxTripsPerOd;
    public int lookAheadEdges;

    // Demand timing controls.
    // tripStartMode=immediate preserves the old behavior.
    // tripStartMode=hourly samples request times from hourlyDemandWeights across simulationDays.
    public String tripStartMode;
    public int simulationDays;
    public List<Double> hourlyDemandWeights;
    public double memoryLearningRate;
    public boolean repositionUsesBaseCost;

    // Choice-model scaling. When true, continuous route-choice features are z-normalized
    // within each candidate route choice set.
    public boolean normalizeChoiceUtility;

    // Reroute search / logging controls. Defaults match the previous hard-coded behavior.
    public int rerouteTopK;
    public double rerouteMaxRelTime;
    public double rerouteMaxEdgeJaccard;
    public boolean debugRerouting;

    public String edgeHabitMode;
    public double edgeHabitScale;

    // When enabled, completed service routes are remembered as additional future
    // candidate routes for the same OD. This lets rerouted/experienced paths become
    // part of the driver population's route choice set.
    public boolean learnObservedRoutesAsCandidates;
    public int learnedRouteMaxPerOd;
    public int learnedRouteMinEdges;

    public List<Double> betaTimeGrid;
    public List<Double> betaMemoryGrid;
    public List<Double> betaEdgeHabitGrid;
    public List<Double> betaDetourGrid;
    public List<Double> betaComplexityGrid;
    public List<Double> decisionNoiseStdGrid;
    public List<Double> slowdownTriggerRatioGrid;
    public List<Double> slowdownTriggerSecGrid;
    public List<Double> rerouteGainThresholdSecGrid;
    public List<Double> rerouteCooldownSecGrid;
    public List<Integer> maxReroutesGrid;

    // Traffic-physics sweep dimensions. Defaults preserve the previous hard-coded
    // congestion equation and the legacy backgroundTrafficScale property.
    public List<Double> bprAlphaGrid;
    public List<Double> bprBetaGrid;
    public List<Double> capacityScaleGrid;
    public List<Double> backgroundTrafficScaleGrid;

    public int nThreads;

    // Objective-metric controls. All weights are applied after normalization.
    public boolean objectiveEnabled;
    public double objectiveMinimumCompletionRate;
    public double objectiveWeightFinalRouteShare;
    public double objectiveWeightEdgeFlow;
    public double objectiveWeightTravelTime;
    public double objectiveWeightNovelty;
    public double objectiveScaleFinalRouteShare;
    public double objectiveScaleEdgeFlow;
    public double objectiveScaleTravelTimeSec;
    public double objectiveScaleNovelty;
    // Set to a value in [0,1] to force a global observed novelty target.
    // Set to -1 to infer residual mass from stable-route shares per OD.
    public double objectiveObservedNoveltyRate;

    // Realized-route classification controls.
    public int routeMatchShortlistTopK;
    public double routeMatchShortlistMinJaccard;
    public double routeMatchNlcsWeight;
    public double routeMatchBigramWeight;
    public double routeMatchMinScore;
    public double routeMatchMinMargin;


    public static SweepConfig load(Path path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        }
        SweepConfig c = new SweepConfig();
        c.candidateRoutes = Path.of(require(props, "candidateRoutes"));
        c.routeWaypoints = Path.of(require(props, "routeWaypoints"));
        c.edgeLookup = Path.of(require(props, "edgeLookup"));
        c.stableRoutes = Path.of(require(props, "stableRoutes"));
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        c.outputDir = Path.of(require(props, "outputDir").replace("{timestamp}", timestamp));
        c.rerouteTimeGraphCsv = Path.of(require(props, "rerouteTimeGraphCsv"));
        c.rerouteLengthGraphCsv = Path.of(require(props, "rerouteLengthGraphCsv"));
        String backgroundTrafficRaw = props.getProperty("backgroundTrafficCsv", "").trim();
        c.backgroundTrafficCsv = backgroundTrafficRaw.isEmpty() ? null : Path.of(backgroundTrafficRaw);
        c.backgroundTrafficScale = Double.parseDouble(props.getProperty("backgroundTrafficScale", "1.0"));
        if (!Double.isFinite(c.backgroundTrafficScale) || c.backgroundTrafficScale < 0.0) {
            throw new IllegalArgumentException("backgroundTrafficScale must be a finite non-negative number.");
        }

        String taxiClassesRaw = props.getProperty("taxiClassesCsv", "").trim();
        c.taxiClassesCsv = taxiClassesRaw.isEmpty() ? null : Path.of(taxiClassesRaw);
        c.taxiClasses = c.taxiClassesCsv == null
                ? List.of()
                : TaxiClassProfile.load(c.taxiClassesCsv);

        c.seed = Long.parseLong(props.getProperty("seed", "42"));
        c.nTaxis = Integer.parseInt(props.getProperty("nTaxis", "100"));
        c.totalTrips = Integer.parseInt(props.getProperty("totalTrips", "1000"));
        c.maxTripsPerOd = Integer.parseInt(props.getProperty("maxTripsPerOd", "0"));
        c.lookAheadEdges = Integer.parseInt(props.getProperty("lookAheadEdges", "4"));
        c.tripStartMode = props.getProperty("tripStartMode", "immediate").trim().toLowerCase();
        c.simulationDays = Math.max(1, Integer.parseInt(props.getProperty("simulationDays", "1")));
        c.hourlyDemandWeights = parseDoubleList(props.getProperty(
                "hourlyDemandWeights",
                "1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1"
        ));
        if (!c.tripStartMode.equals("immediate") && !c.tripStartMode.equals("hourly")) {
            throw new IllegalArgumentException("Invalid tripStartMode: " + c.tripStartMode + ". Use immediate or hourly.");
        }
        if (c.hourlyDemandWeights.size() != 24) {
            throw new IllegalArgumentException("hourlyDemandWeights must contain exactly 24 comma-separated values.");
        }
        double demandWeightSum = 0.0;
        for (double w : c.hourlyDemandWeights) {
            if (!Double.isFinite(w) || w < 0.0) {
                throw new IllegalArgumentException("hourlyDemandWeights must be finite non-negative values.");
            }
            demandWeightSum += w;
        }
        if (demandWeightSum <= 0.0) {
            throw new IllegalArgumentException("At least one hourlyDemandWeights value must be positive.");
        }
        c.memoryLearningRate = Double.parseDouble(props.getProperty("memoryLearningRate", "0.2"));
        c.repositionUsesBaseCost = Boolean.parseBoolean(props.getProperty("repositionUsesBaseCost", "true"));
        c.normalizeChoiceUtility = Boolean.parseBoolean(props.getProperty("normalizeChoiceUtility", "true"));
        c.rerouteTopK = Integer.parseInt(props.getProperty("rerouteTopK", "3"));
        c.rerouteMaxRelTime = Double.parseDouble(props.getProperty("rerouteMaxRelTime", "1.35"));
        c.rerouteMaxEdgeJaccard = Double.parseDouble(props.getProperty("rerouteMaxEdgeJaccard", "0.85"));
        c.debugRerouting = Boolean.parseBoolean(props.getProperty("debugRerouting", "false"));

        c.edgeHabitMode = props.getProperty("edgeHabitMode", "off").trim().toLowerCase();
        c.edgeHabitScale = Double.parseDouble(props.getProperty("edgeHabitScale", "10.0"));
        c.learnObservedRoutesAsCandidates = Boolean.parseBoolean(props.getProperty("learnObservedRoutesAsCandidates", "false"));
        c.learnedRouteMaxPerOd = Integer.parseInt(props.getProperty("learnedRouteMaxPerOd", "20"));
        c.learnedRouteMinEdges = Integer.parseInt(props.getProperty("learnedRouteMinEdges", "2"));

        if (!c.edgeHabitMode.equals("off") && !c.edgeHabitMode.equals("collective") && !c.edgeHabitMode.equals("taxi")) {
            throw new IllegalArgumentException("Invalid edgeHabitMode: " + c.edgeHabitMode + ". Use off, collective, or taxi.");
        }
        if (!Double.isFinite(c.edgeHabitScale) || c.edgeHabitScale <= 0.0) {
            throw new IllegalArgumentException("edgeHabitScale must be positive.");
        }
        if (c.learnedRouteMaxPerOd < 0) {
            throw new IllegalArgumentException("learnedRouteMaxPerOd must be non-negative.");
        }
        if (c.learnedRouteMinEdges < 1) {
            throw new IllegalArgumentException("learnedRouteMinEdges must be at least 1.");
        }

        c.betaTimeGrid = parseDoubleList(props.getProperty("betaTimeGrid", "1.0"));
        c.betaMemoryGrid = parseDoubleList(props.getProperty("betaMemoryGrid", "0.25"));
        c.betaEdgeHabitGrid = parseDoubleList(props.getProperty("betaEdgeHabitGrid", "0.0"));
        c.betaDetourGrid = parseDoubleList(props.getProperty("betaDetourGrid", "0.0"));
        c.betaComplexityGrid = parseDoubleList(props.getProperty("betaComplexityGrid", "0.0"));
        c.decisionNoiseStdGrid = parseDoubleList(props.getProperty("decisionNoiseStdGrid", "15.0"));
        c.slowdownTriggerRatioGrid = parseDoubleList(props.getProperty("slowdownTriggerRatioGrid", "1.5"));
        c.slowdownTriggerSecGrid = parseDoubleList(props.getProperty("slowdownTriggerSecGrid", "30.0"));
        c.rerouteGainThresholdSecGrid = parseDoubleList(props.getProperty("rerouteGainThresholdSecGrid", "10.0"));
        c.rerouteCooldownSecGrid = parseDoubleList(props.getProperty("rerouteCooldownSecGrid", "60.0"));
        c.maxReroutesGrid = parseIntList(props.getProperty("maxReroutesGrid", "2"));

        c.bprAlphaGrid = parseDoubleList(props.getProperty("bprAlphaGrid", "0.5"));
        c.bprBetaGrid = parseDoubleList(props.getProperty("bprBetaGrid", "2.0"));
        c.capacityScaleGrid = parseDoubleList(props.getProperty("capacityScaleGrid", "1.0"));
        c.backgroundTrafficScaleGrid = parseDoubleList(props.getProperty(
                "backgroundTrafficScaleGrid",
                Double.toString(c.backgroundTrafficScale)
        ));
        validateTrafficPhysicsGrid(c.bprAlphaGrid, "bprAlphaGrid", true);
        validateTrafficPhysicsGrid(c.bprBetaGrid, "bprBetaGrid", false);
        validateTrafficPhysicsGrid(c.capacityScaleGrid, "capacityScaleGrid", false);
        validateTrafficPhysicsGrid(c.backgroundTrafficScaleGrid, "backgroundTrafficScaleGrid", true);

        c.nThreads = Integer.parseInt(props.getProperty(
                "nThreads",
                String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors() - 1))
        ));
        c.topKOutputs = Integer.parseInt(props.getProperty("topKOutputs", "10"));

        c.objectiveEnabled = Boolean.parseBoolean(props.getProperty("objectiveEnabled", "true"));
        c.objectiveMinimumCompletionRate = Double.parseDouble(props.getProperty("objectiveMinimumCompletionRate", "0.995"));
        c.objectiveWeightFinalRouteShare = Double.parseDouble(props.getProperty("objectiveWeightFinalRouteShare", "0.40"));
        c.objectiveWeightEdgeFlow = Double.parseDouble(props.getProperty("objectiveWeightEdgeFlow", "0.25"));
        c.objectiveWeightTravelTime = Double.parseDouble(props.getProperty("objectiveWeightTravelTime", "0.25"));
        c.objectiveWeightNovelty = Double.parseDouble(props.getProperty("objectiveWeightNovelty", "0.10"));
        c.objectiveScaleFinalRouteShare = Double.parseDouble(props.getProperty("objectiveScaleFinalRouteShare", "0.05"));
        c.objectiveScaleEdgeFlow = Double.parseDouble(props.getProperty("objectiveScaleEdgeFlow", "0.10"));
        c.objectiveScaleTravelTimeSec = Double.parseDouble(props.getProperty("objectiveScaleTravelTimeSec", "30.0"));
        c.objectiveScaleNovelty = Double.parseDouble(props.getProperty("objectiveScaleNovelty", "0.02"));
        c.objectiveObservedNoveltyRate = Double.parseDouble(props.getProperty("objectiveObservedNoveltyRate", "-1.0"));

        c.routeMatchShortlistTopK = Integer.parseInt(props.getProperty("routeMatchShortlistTopK", "3"));
        c.routeMatchShortlistMinJaccard = Double.parseDouble(props.getProperty("routeMatchShortlistMinJaccard", "0.20"));
        c.routeMatchNlcsWeight = Double.parseDouble(props.getProperty("routeMatchNlcsWeight", "0.70"));
        c.routeMatchBigramWeight = Double.parseDouble(props.getProperty("routeMatchBigramWeight", "0.30"));
        c.routeMatchMinScore = Double.parseDouble(props.getProperty("routeMatchMinScore", "0.70"));
        c.routeMatchMinMargin = Double.parseDouble(props.getProperty("routeMatchMinMargin", "0.10"));

        c.validateObjectiveSettings();
        return c;
    }

    private void validateObjectiveSettings() {
        requireRate(objectiveMinimumCompletionRate, "objectiveMinimumCompletionRate");
        requireNonNegative(objectiveWeightFinalRouteShare, "objectiveWeightFinalRouteShare");
        requireNonNegative(objectiveWeightEdgeFlow, "objectiveWeightEdgeFlow");
        requireNonNegative(objectiveWeightTravelTime, "objectiveWeightTravelTime");
        requireNonNegative(objectiveWeightNovelty, "objectiveWeightNovelty");
        double weightSum = objectiveWeightFinalRouteShare + objectiveWeightEdgeFlow
                + objectiveWeightTravelTime + objectiveWeightNovelty;
        if (!Double.isFinite(weightSum) || weightSum <= 0.0) {
            throw new IllegalArgumentException("Objective weights must sum to a positive value.");
        }
        requirePositive(objectiveScaleFinalRouteShare, "objectiveScaleFinalRouteShare");
        requirePositive(objectiveScaleEdgeFlow, "objectiveScaleEdgeFlow");
        requirePositive(objectiveScaleTravelTimeSec, "objectiveScaleTravelTimeSec");
        requirePositive(objectiveScaleNovelty, "objectiveScaleNovelty");
        if (!Double.isFinite(objectiveObservedNoveltyRate)
                || (objectiveObservedNoveltyRate != -1.0
                && (objectiveObservedNoveltyRate < 0.0 || objectiveObservedNoveltyRate > 1.0))) {
            throw new IllegalArgumentException("objectiveObservedNoveltyRate must be -1 or a value in [0,1].");
        }
        if (routeMatchShortlistTopK < 0) {
            throw new IllegalArgumentException("routeMatchShortlistTopK must be non-negative; 0 means all routes.");
        }
        requireRate(routeMatchShortlistMinJaccard, "routeMatchShortlistMinJaccard");
        requireNonNegative(routeMatchNlcsWeight, "routeMatchNlcsWeight");
        requireNonNegative(routeMatchBigramWeight, "routeMatchBigramWeight");
        if (routeMatchNlcsWeight + routeMatchBigramWeight <= 0.0) {
            throw new IllegalArgumentException("Route-match weights must sum to a positive value.");
        }
        requireRate(routeMatchMinScore, "routeMatchMinScore");
        requireRate(routeMatchMinMargin, "routeMatchMinMargin");
    }

    public String objectivePropertiesText() {
        String nl = System.lineSeparator();
        return "objectiveEnabled=" + objectiveEnabled + nl
                + "objectiveMinimumCompletionRate=" + objectiveMinimumCompletionRate + nl
                + "objectiveWeightFinalRouteShare=" + objectiveWeightFinalRouteShare + nl
                + "objectiveWeightEdgeFlow=" + objectiveWeightEdgeFlow + nl
                + "objectiveWeightTravelTime=" + objectiveWeightTravelTime + nl
                + "objectiveWeightNovelty=" + objectiveWeightNovelty + nl
                + "objectiveScaleFinalRouteShare=" + objectiveScaleFinalRouteShare + nl
                + "objectiveScaleEdgeFlow=" + objectiveScaleEdgeFlow + nl
                + "objectiveScaleTravelTimeSec=" + objectiveScaleTravelTimeSec + nl
                + "objectiveScaleNovelty=" + objectiveScaleNovelty + nl
                + "objectiveObservedNoveltyRate=" + objectiveObservedNoveltyRate + nl
                + "routeMatchShortlistTopK=" + routeMatchShortlistTopK + nl
                + "routeMatchShortlistMinJaccard=" + routeMatchShortlistMinJaccard + nl
                + "routeMatchNlcsWeight=" + routeMatchNlcsWeight + nl
                + "routeMatchBigramWeight=" + routeMatchBigramWeight + nl
                + "routeMatchMinScore=" + routeMatchMinScore + nl
                + "routeMatchMinMargin=" + routeMatchMinMargin + nl;
    }

    private static void requireRate(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0,1].");
        }
    }

    private static void requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative.");
        }
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive.");
        }
    }


    private static void validateTrafficPhysicsGrid(List<Double> values, String name, boolean allowZero) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(name + " must contain at least one value.");
        }
        for (double value : values) {
            boolean invalid = !Double.isFinite(value) || (allowZero ? value < 0.0 : value <= 0.0);
            if (invalid) {
                throw new IllegalArgumentException(name + " contains invalid value: " + value);
            }
        }
    }

    private static String require(Properties props, String key) {
        String v = props.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required property: " + key);
        }
        return v.trim();
    }

    private static List<Double> parseDoubleList(String raw) {
        List<Double> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String v = s.trim();
            if (!v.isEmpty()) {
                out.add(Double.parseDouble(v));
            }
        }
        return out;
    }

    private static List<Integer> parseIntList(String raw) {
        List<Integer> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String v = s.trim();
            if (!v.isEmpty()) {
                out.add(Integer.parseInt(v));
            }
        }
        return out;
    }
}
