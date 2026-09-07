package porto.sweep.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TripOutcome {
    private final String simTripId;
    private final int taxiId;
    private final String taxiClass;
    private final int completedTripNo;
    private final String odId;
    private final String initialRouteId;
    private final String finalRouteId;
    private final int rerouteCount;
    private final double requestedStartTimeSec;
    private final double startTimeSec;
    private final double finishTimeSec;
    private final double expectedTimeSec;
    private final double actualTimeSec;
    private final List<String> realizedEdgeSeq;
    private final List<Double> realizedEdgeTravelSecSeq;
    private final List<String> realizedNodeSeq;
    private final double bestJaccard;
    private final double routeMatchScore;
    private final double routeMatchSecondScore;
    private final double routeMatchMargin;
    private final String routeMatchStatus;
    private final String nearestRealRouteId;
    private final String matchedRealRouteId;

    public TripOutcome(String simTripId, int taxiId, String taxiClass, int completedTripNo, String odId,
                       String initialRouteId, String finalRouteId, int rerouteCount,
                       double requestedStartTimeSec, double startTimeSec, double finishTimeSec,
                       double expectedTimeSec, double actualTimeSec,
                       List<String> realizedEdgeSeq, List<Double> realizedEdgeTravelSecSeq, List<String> realizedNodeSeq,
                       double bestJaccard, double routeMatchScore, double routeMatchSecondScore,
                       double routeMatchMargin, String routeMatchStatus,
                       String nearestRealRouteId, String matchedRealRouteId) {
        this.simTripId = simTripId;
        this.taxiId = taxiId;
        this.taxiClass = taxiClass == null ? "" : taxiClass;
        this.completedTripNo = completedTripNo;
        this.odId = odId;
        this.initialRouteId = initialRouteId;
        this.finalRouteId = finalRouteId;
        this.rerouteCount = rerouteCount;
        this.requestedStartTimeSec = requestedStartTimeSec;
        this.startTimeSec = startTimeSec;
        this.finishTimeSec = finishTimeSec;
        this.expectedTimeSec = expectedTimeSec;
        this.actualTimeSec = actualTimeSec;
        this.realizedEdgeSeq = new ArrayList<>(realizedEdgeSeq);
        this.realizedEdgeTravelSecSeq = new ArrayList<>(realizedEdgeTravelSecSeq);
        this.realizedNodeSeq = new ArrayList<>(realizedNodeSeq);
        this.bestJaccard = bestJaccard;
        this.routeMatchScore = routeMatchScore;
        this.routeMatchSecondScore = routeMatchSecondScore;
        this.routeMatchMargin = routeMatchMargin;
        this.routeMatchStatus = routeMatchStatus == null ? "" : routeMatchStatus;
        this.nearestRealRouteId = nearestRealRouteId == null ? "" : nearestRealRouteId;
        this.matchedRealRouteId = matchedRealRouteId == null ? "" : matchedRealRouteId;
    }

    public String getOdId() {
        return odId;
    }

    public String getInitialRouteId() {
        return initialRouteId;
    }

    public String getFinalRouteId() {
        return finalRouteId;
    }

    public String getMatchedRealRouteId() {
        return matchedRealRouteId;
    }

    public String getNearestRealRouteId() {
        return nearestRealRouteId;
    }

    public String getRouteMatchStatus() {
        return routeMatchStatus;
    }

    public double getRouteMatchScore() {
        return routeMatchScore;
    }

    public double getRouteMatchMargin() {
        return routeMatchMargin;
    }

    public int getRerouteCount() {
        return rerouteCount;
    }

    public double getActualTimeSec() {
        return actualTimeSec;
    }

    public double getBestJaccard() {
        return bestJaccard;
    }

    public double getRequestedStartTimeSec() {
        return requestedStartTimeSec;
    }

    public double getStartTimeSec() {
        return startTimeSec;
    }

    public double getFinishTimeSec() {
        return finishTimeSec;
    }

    public List<String> getRealizedEdgeSeq() {
        return new ArrayList<>(realizedEdgeSeq);
    }

    public List<Double> getRealizedEdgeTravelSecSeq() {
        return new ArrayList<>(realizedEdgeTravelSecSeq);
    }

    public static String csvHeader() {
        return "sim_trip_id,taxi_id,taxi_class,completed_trip_no,od_id,initial_route_id,final_route_id,reroute_count," +
                "requested_start_time_sec,start_time_sec,finish_time_sec,requested_start_clock,start_clock,finish_clock," +
                "expected_time_sec,actual_time_sec,realized_edge_seq,realized_edge_travel_sec_seq,realized_node_seq," +
                "best_jaccard,route_match_score,route_match_second_score,route_match_margin,route_match_status," +
                "nearest_real_route_id,matched_real_route_id";
    }

    public String toCsvRow() {
        return csv(simTripId) + "," +
                taxiId + "," +
                csv(taxiClass) + "," +
                completedTripNo + "," +
                csv(odId) + "," +
                csv(initialRouteId) + "," +
                csv(finalRouteId) + "," +
                rerouteCount + "," +
                requestedStartTimeSec + "," +
                startTimeSec + "," +
                finishTimeSec + "," +
                csv(formatClock(requestedStartTimeSec)) + "," +
                csv(formatClock(startTimeSec)) + "," +
                csv(formatClock(finishTimeSec)) + "," +
                expectedTimeSec + "," +
                actualTimeSec + "," +
                csv(String.join(";", realizedEdgeSeq)) + "," +
                csv(joinDoubleSeq(realizedEdgeTravelSecSeq)) + "," +
                csv(String.join(";", realizedNodeSeq)) + "," +
                bestJaccard + "," +
                routeMatchScore + "," +
                routeMatchSecondScore + "," +
                routeMatchMargin + "," +
                csv(routeMatchStatus) + "," +
                csv(nearestRealRouteId) + "," +
                csv(matchedRealRouteId);
    }

    private static String joinDoubleSeq(List<Double> values) {
        List<String> out = new ArrayList<>();
        for (double v : values) {
            out.add(String.format(Locale.US, "%.6f", v));
        }
        return String.join(";", out);
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

    public static String csv(String value) {
        String v = value == null ? "" : value;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
