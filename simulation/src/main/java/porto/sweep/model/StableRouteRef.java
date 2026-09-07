package porto.sweep.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class StableRouteRef {
    private final String odId;
    private final String routeId;
    private final double stableShare;
    private final double avgTimeSec;
    private final int odTripCount;
    private final List<String> nodeSeq;
    private final List<String> edgeSeq;
    private final Set<String> edgeSet;

    public StableRouteRef(String odId, String routeId, double stableShare, double avgTimeSec, int odTripCount,
                          List<String> nodeSeq, List<String> edgeSeq) {
        this.odId = odId;
        this.routeId = routeId;
        this.stableShare = stableShare;
        this.avgTimeSec = avgTimeSec;
        this.odTripCount = odTripCount;
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

    public double getStableShare() {
        return stableShare;
    }

    public double getAvgTimeSec() {
        return avgTimeSec;
    }

    public int getOdTripCount() {
        return odTripCount;
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
}
