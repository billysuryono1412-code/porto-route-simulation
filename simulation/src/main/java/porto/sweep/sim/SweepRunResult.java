package porto.sweep.sim;

public class SweepRunResult {
    public final SweepParameters parameters;
    public final int tripCount;
    public final double meanBestJaccard;
    public final double meanRouteMatchScore;
    public final double meanActualSec;
    public final double meanReroutes;
    public final double completionRate;
    public final double dominantRouteMatchRate;

    public final double finalRouteShareJsd;
    public final double finalRouteShareMae;
    public final double edgeFlowWmae;
    public final double travelTimeWmaeSec;
    public final double simulatedNoveltyRate;
    public final double observedNoveltyRate;
    public final double noveltyRateError;

    public final double normalizedFinalRouteShareError;
    public final double normalizedEdgeFlowError;
    public final double normalizedTravelTimeError;
    public final double normalizedNoveltyError;
    public final double objectiveLoss;
    public final boolean objectiveEnabled;
    public final boolean objectiveEligible;

    public final int matchedTripCount;
    public final int unmatchedTripCount;
    public final int ambiguousTripCount;
    public final int noReferenceTripCount;
    public final int travelTimeComparableTripCount;
    public final double runtimeSec;

    public SweepRunResult(
            SweepParameters parameters,
            int tripCount,
            double meanBestJaccard,
            double meanRouteMatchScore,
            double meanActualSec,
            double meanReroutes,
            double completionRate,
            double dominantRouteMatchRate,
            double finalRouteShareJsd,
            double finalRouteShareMae,
            double edgeFlowWmae,
            double travelTimeWmaeSec,
            double simulatedNoveltyRate,
            double observedNoveltyRate,
            double noveltyRateError,
            double normalizedFinalRouteShareError,
            double normalizedEdgeFlowError,
            double normalizedTravelTimeError,
            double normalizedNoveltyError,
            double objectiveLoss,
            boolean objectiveEnabled,
            boolean objectiveEligible,
            int matchedTripCount,
            int unmatchedTripCount,
            int ambiguousTripCount,
            int noReferenceTripCount,
            int travelTimeComparableTripCount,
            double runtimeSec
    ) {
        this.parameters = parameters;
        this.tripCount = tripCount;
        this.meanBestJaccard = meanBestJaccard;
        this.meanRouteMatchScore = meanRouteMatchScore;
        this.meanActualSec = meanActualSec;
        this.meanReroutes = meanReroutes;
        this.completionRate = completionRate;
        this.dominantRouteMatchRate = dominantRouteMatchRate;
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
        this.objectiveEnabled = objectiveEnabled;
        this.objectiveEligible = objectiveEligible;
        this.matchedTripCount = matchedTripCount;
        this.unmatchedTripCount = unmatchedTripCount;
        this.ambiguousTripCount = ambiguousTripCount;
        this.noReferenceTripCount = noReferenceTripCount;
        this.travelTimeComparableTripCount = travelTimeComparableTripCount;
        this.runtimeSec = runtimeSec;
    }

    public static String csvHeader() {
        return "routeChoiceMode,betaTime,betaMemory,betaEdgeHabit,betaDetour,betaComplexity,decisionNoiseStd," +
                "slowdownTriggerRatio,slowdownTriggerSec,rerouteGainThresholdSec,rerouteCooldownSec,maxReroutes," +
                "bprAlpha,bprBeta,capacityScale,backgroundTrafficScale," +
                "objectiveEnabled,objectiveEligible,objectiveLoss,finalRouteShareJsd,finalRouteShareMae," +
                "edgeFlowWmae,travelTimeWmaeSec,simulatedNoveltyRate,observedNoveltyRate,noveltyRateError," +
                "normalizedFinalRouteShareError,normalizedEdgeFlowError,normalizedTravelTimeError,normalizedNoveltyError," +
                "meanBestJaccard,meanRouteMatchScore,meanActualSec,meanReroutes,completionRate,dominantRouteMatchRate," +
                "matchedTripCount,unmatchedTripCount,ambiguousTripCount,noReferenceTripCount," +
                "travelTimeComparableTripCount,runtimeSec,tripCount";
    }

    public String toCsvRow() {
        return parameters.routeChoiceMode + "," + parameters.betaTime + "," + parameters.betaMemory + "," +
                parameters.betaEdgeHabit + "," + parameters.betaDetour + "," + parameters.betaComplexity + "," +
                parameters.decisionNoiseStd + "," + parameters.slowdownTriggerRatio + "," +
                parameters.slowdownTriggerSec + "," + parameters.rerouteGainThresholdSec + "," +
                parameters.rerouteCooldownSec + "," + parameters.maxReroutes + "," +
                parameters.bprAlpha + "," + parameters.bprBeta + "," + parameters.capacityScale + "," +
                parameters.backgroundTrafficScale + "," +
                objectiveEnabled + "," + objectiveEligible + "," + objectiveLoss + "," +
                finalRouteShareJsd + "," + finalRouteShareMae + "," + edgeFlowWmae + "," +
                travelTimeWmaeSec + "," + simulatedNoveltyRate + "," + observedNoveltyRate + "," +
                noveltyRateError + "," + normalizedFinalRouteShareError + "," + normalizedEdgeFlowError + "," +
                normalizedTravelTimeError + "," + normalizedNoveltyError + "," + meanBestJaccard + "," +
                meanRouteMatchScore + "," + meanActualSec + "," + meanReroutes + "," + completionRate + "," +
                dominantRouteMatchRate + "," + matchedTripCount + "," + unmatchedTripCount + "," +
                ambiguousTripCount + "," + noReferenceTripCount + "," + travelTimeComparableTripCount + "," +
                runtimeSec + "," + tripCount;
    }
}
