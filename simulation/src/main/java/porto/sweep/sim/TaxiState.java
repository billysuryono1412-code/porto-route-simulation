package porto.sweep.sim;

import porto.sweep.config.TaxiClassProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class TaxiState {
    enum Mode {IDLE, WAITING, REPOSITIONING, SERVING}

    final int taxiId;
    final TaxiClassProfile taxiClassProfile;
    Mode mode = Mode.IDLE;

    String currentNode = "";
    String odId = "";
    String destinationNode = "";
    String initialRouteId = "";
    String finalRouteId = "";
    int completedTrips = 0;
    int rerouteCount = 0;

    List<String> plannedNodes = new ArrayList<>();
    List<String> plannedEdges = new ArrayList<>();
    List<String> realizedNodes = new ArrayList<>();
    List<String> realizedEdges = new ArrayList<>();
    List<Double> realizedEdgeTravelSec = new ArrayList<>();

    int nextEdgeIndex = 0;
    double stateStartTimeSec = 0.0;
    double serviceStartTimeSec = 0.0;
    double requestedStartTimeSec = 0.0;
    double nextEventTimeSec = Double.POSITIVE_INFINITY;
    double lastRerouteDecisionTimeSec = -1e18;
    double expectedTripTimeSec = 0.0;
    double accumulatedTripTimeSec = 0.0;
    String traversingEdgeId = "";

    final Map<String, Double> edgeMemoryPenalty = new HashMap<>();
    final Map<String, Integer> edgeMemoryCount = new HashMap<>();
    final Map<String, Integer> edgeHabitCounts = new HashMap<>();

    TaxiState(int taxiId) {
        this(taxiId, null);
    }

    TaxiState(int taxiId, TaxiClassProfile taxiClassProfile) {
        this.taxiId = taxiId;
        this.taxiClassProfile = taxiClassProfile;
    }

    String getTaxiClassId() {
        return taxiClassProfile == null ? "homogeneous" : taxiClassProfile.classId;
    }

    void resetForService(String odId, String initialRouteId, String destinationNode, double stateStartTimeSec, double requestedStartTimeSec) {
        this.mode = Mode.SERVING;
        this.odId = odId;
        this.initialRouteId = initialRouteId;
        this.finalRouteId = initialRouteId;
        this.destinationNode = destinationNode;
        this.stateStartTimeSec = stateStartTimeSec;
        this.serviceStartTimeSec = stateStartTimeSec;
        this.requestedStartTimeSec = requestedStartTimeSec;
        this.rerouteCount = 0;
        this.realizedNodes = new ArrayList<>();
        this.realizedEdges = new ArrayList<>();
        this.realizedEdgeTravelSec = new ArrayList<>();
        this.accumulatedTripTimeSec = 0.0;
        this.expectedTripTimeSec = 0.0;
        this.lastRerouteDecisionTimeSec = -1e18;
        this.traversingEdgeId = "";
    }

    void resetForWaiting(String odId, double requestedStartTimeSec, double wakeTimeSec) {
        this.mode = Mode.WAITING;
        this.odId = odId;
        this.requestedStartTimeSec = requestedStartTimeSec;
        this.nextEventTimeSec = wakeTimeSec;
        this.traversingEdgeId = "";
        this.plannedNodes = new ArrayList<>();
        this.plannedEdges = new ArrayList<>();
        this.nextEdgeIndex = 0;
        this.accumulatedTripTimeSec = 0.0;
    }

    double getMemoryPenalty(String edgeId) {
        return edgeMemoryPenalty.getOrDefault(edgeId, 0.0);
    }

    void updateEdgeMemory(String edgeId, double penaltySec, double learningRate) {
        double old = edgeMemoryPenalty.getOrDefault(edgeId, 0.0);
        double updated = (1.0 - learningRate) * old + learningRate * penaltySec;
        edgeMemoryPenalty.put(edgeId, updated);
        edgeMemoryCount.put(edgeId, edgeMemoryCount.getOrDefault(edgeId, 0) + 1);
    }
}
