package porto.sweep.model;

import java.util.Arrays;

public class EdgeStats {
    private final String edgeId;

    private final double[] hourMeanSec = new double[24];
    private double allDayMeanSec = Double.NaN;

    private final double fallbackMeanSec;
    private final double traversalCount;

    public EdgeStats(String edgeId, double fallbackMeanSec, double traversalCount) {
        this.edgeId = edgeId;
        this.fallbackMeanSec = fallbackMeanSec;
        this.traversalCount = traversalCount;

        Arrays.fill(hourMeanSec, Double.NaN);
    }

    public String getEdgeId() {
        return edgeId;
    }

    public void putHourMean(String hourKey, double meanSec) {
        if (hourKey == null || hourKey.isBlank()) {
            return;
        }

        if (hourKey.equalsIgnoreCase("all_day")) {
            allDayMeanSec = meanSec;
            return;
        }

        try {
            int hour = Integer.parseInt(hourKey.trim());

            if (hour >= 0 && hour < 24) {
                hourMeanSec[hour] = meanSec;
            }
        } catch (NumberFormatException ignored) {
        }
    }

    public double getBaseCostSec(int hour) {
        int h = ((hour % 24) + 24) % 24;

        double value = hourMeanSec[h];

        if (!Double.isNaN(value)) {
            return value;
        }

        if (!Double.isNaN(allDayMeanSec)) {
            return allDayMeanSec;
        }

        return fallbackMeanSec;
    }

    public double getFallbackMeanSec() {
        return fallbackMeanSec;
    }

    public double getTraversalCount() {
        return traversalCount;
    }
}