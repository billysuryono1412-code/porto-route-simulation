package porto.sweep.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class RouteData {
    private final String odId;
    private final String routeId;
    private final double priorShare;
    private final double avgTimeSec;
    private final double avgDistanceM;
    private final int routeRank;
    private final List<String> nodeSeq;
    private final List<String> edgeSeq;
    private final Set<String> edgeSet;

    public RouteData(String odId, String routeId, double priorShare, double avgTimeSec, double avgDistanceM,
                     int routeRank, List<String> nodeSeq, List<String> edgeSeq) {
        this.odId = odId;
        this.routeId = routeId;
        this.priorShare = priorShare;
        this.avgTimeSec = avgTimeSec;
        this.avgDistanceM = avgDistanceM;
        this.routeRank = routeRank;
        this.nodeSeq = new ArrayList<>(nodeSeq);
        this.edgeSeq = new ArrayList<>(edgeSeq);
        this.edgeSet = new LinkedHashSet<>(edgeSeq);
    }

    public String getOdId() {
        return odId;
    }

    public String getRouteId() {
        return routeId;
    }

    public double getPriorShare() {
        return priorShare;
    }

    public double getAvgTimeSec() {
        return avgTimeSec;
    }

    public double getAvgDistanceM() {
        return avgDistanceM;
    }

    public int getRouteRank() {
        return routeRank;
    }

    public List<String> getNodeSeq() {
        return nodeSeq;
    }

    public List<String> getEdgeSeq() {
        return edgeSeq;
    }

    public Set<String> getEdgeSet() {
        return edgeSet;
    }

    public String getOriginNode() {
        return nodeSeq.isEmpty() ? "" : nodeSeq.get(0);
    }

    public String getDestinationNode() {
        return nodeSeq.isEmpty() ? "" : nodeSeq.get(nodeSeq.size() - 1);
    }
}
