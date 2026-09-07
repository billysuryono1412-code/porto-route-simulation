package porto.sweep.sim;

public class SweepParameters {
    public static final String CHOICE_BEHAVIORAL = "behavioral";
    public static final String CHOICE_SHORTEST = "shortest";
    public static final String CHOICE_STATIC_FASTEST = "static_fastest";
    public static final String CHOICE_DYNAMIC_FASTEST = "dynamic_fastest";

    public final String routeChoiceMode;
    public final double betaTime;
    public final double betaMemory;
    public final double betaEdgeHabit;
    public final double betaDetour;
    public final double betaComplexity;
    public final double decisionNoiseStd;
    public final double slowdownTriggerRatio;
    public final double slowdownTriggerSec;
    public final double rerouteGainThresholdSec;
    public final double rerouteCooldownSec;
    public final int maxReroutes;

    // Run-level traffic-physics parameters. These are intentionally not taxi-class
    // parameters: every taxi in one simulation run shares the same network physics.
    public final double bprAlpha;
    public final double bprBeta;
    public final double capacityScale;
    public final double backgroundTrafficScale;

    public SweepParameters(double betaTime, double betaMemory,
                           double betaEdgeHabit, double betaDetour, double betaComplexity,
                           double decisionNoiseStd,
                           double slowdownTriggerRatio, double slowdownTriggerSec,
                           double rerouteGainThresholdSec, double rerouteCooldownSec, int maxReroutes) {
        this(betaTime, betaMemory, betaEdgeHabit, betaDetour, betaComplexity, decisionNoiseStd,
                slowdownTriggerRatio, slowdownTriggerSec, rerouteGainThresholdSec, rerouteCooldownSec,
                maxReroutes, 0.5, 2.0, 1.0, 1.0, CHOICE_BEHAVIORAL);
    }

    public SweepParameters(double betaTime, double betaMemory,
                           double betaEdgeHabit, double betaDetour, double betaComplexity,
                           double decisionNoiseStd,
                           double slowdownTriggerRatio, double slowdownTriggerSec,
                           double rerouteGainThresholdSec, double rerouteCooldownSec, int maxReroutes,
                           String routeChoiceMode) {
        this(betaTime, betaMemory, betaEdgeHabit, betaDetour, betaComplexity, decisionNoiseStd,
                slowdownTriggerRatio, slowdownTriggerSec, rerouteGainThresholdSec, rerouteCooldownSec,
                maxReroutes, 0.5, 2.0, 1.0, 1.0, routeChoiceMode);
    }

    public SweepParameters(double betaTime, double betaMemory,
                           double betaEdgeHabit, double betaDetour, double betaComplexity,
                           double decisionNoiseStd,
                           double slowdownTriggerRatio, double slowdownTriggerSec,
                           double rerouteGainThresholdSec, double rerouteCooldownSec, int maxReroutes,
                           double bprAlpha, double bprBeta, double capacityScale, double backgroundTrafficScale,
                           String routeChoiceMode) {
        this.routeChoiceMode = normalizeRouteChoiceMode(routeChoiceMode);
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
        this.bprAlpha = requireNonNegativeFinite(bprAlpha, "bprAlpha");
        this.bprBeta = requirePositiveFinite(bprBeta, "bprBeta");
        this.capacityScale = requirePositiveFinite(capacityScale, "capacityScale");
        this.backgroundTrafficScale = requireNonNegativeFinite(backgroundTrafficScale, "backgroundTrafficScale");
    }

    public String id() {
        // Keep IDs short enough for Windows filenames while retaining enough
        // significant digits to distinguish normal LHS samples.
        return "mode_" + routeChoiceMode + "__bt_" + fmtId(betaTime) + "__bm_" + fmtId(betaMemory)
                + "__beh_" + fmtId(betaEdgeHabit) + "__bd_" + fmtId(betaDetour) + "__bc_" + fmtId(betaComplexity)
                + "__noise_" + fmtId(decisionNoiseStd) + "__ratio_" + fmtId(slowdownTriggerRatio)
                + "__slowsec_" + fmtId(slowdownTriggerSec) + "__gain_" + fmtId(rerouteGainThresholdSec)
                + "__cool_" + fmtId(rerouteCooldownSec) + "__mr_" + maxReroutes
                + "__a_" + fmtId(bprAlpha) + "__b_" + fmtId(bprBeta)
                + "__cap_" + fmtId(capacityScale) + "__bg_" + fmtId(backgroundTrafficScale);
    }

    private static String fmtId(double value) {
        return String.format(java.util.Locale.US, "%.6g", value);
    }

    public String toPropertiesText() {
        return "routeChoiceMode=" + routeChoiceMode + System.lineSeparator()
                + "betaTime=" + betaTime + System.lineSeparator()
                + "betaMemory=" + betaMemory + System.lineSeparator()
                + "betaEdgeHabit=" + betaEdgeHabit + System.lineSeparator()
                + "betaDetour=" + betaDetour + System.lineSeparator()
                + "betaComplexity=" + betaComplexity + System.lineSeparator()
                + "decisionNoiseStd=" + decisionNoiseStd + System.lineSeparator()
                + "slowdownTriggerRatio=" + slowdownTriggerRatio + System.lineSeparator()
                + "slowdownTriggerSec=" + slowdownTriggerSec + System.lineSeparator()
                + "rerouteGainThresholdSec=" + rerouteGainThresholdSec + System.lineSeparator()
                + "rerouteCooldownSec=" + rerouteCooldownSec + System.lineSeparator()
                + "maxReroutes=" + maxReroutes + System.lineSeparator()
                + "bprAlpha=" + bprAlpha + System.lineSeparator()
                + "bprBeta=" + bprBeta + System.lineSeparator()
                + "capacityScale=" + capacityScale + System.lineSeparator()
                + "backgroundTrafficScale=" + backgroundTrafficScale + System.lineSeparator();
    }

    private static String normalizeRouteChoiceMode(String raw) {
        String mode = raw == null ? CHOICE_BEHAVIORAL : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case CHOICE_BEHAVIORAL, CHOICE_SHORTEST, CHOICE_STATIC_FASTEST, CHOICE_DYNAMIC_FASTEST -> mode;
            default -> throw new IllegalArgumentException("Unknown routeChoiceMode: " + raw);
        };
    }

    private static double requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive.");
        }
        return value;
    }

    private static double requireNonNegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative.");
        }
        return value;
    }
}
