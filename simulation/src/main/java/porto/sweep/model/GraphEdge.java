package porto.sweep.model;

public class GraphEdge {
    private final String fromNode;
    private final String toNode;
    private final String edgeId;

    public GraphEdge(String fromNode, String toNode, String edgeId) {
        this.fromNode = fromNode;
        this.toNode = toNode;
        this.edgeId = edgeId;
    }

    public String getFromNode() {
        return fromNode;
    }

    public String getToNode() {
        return toNode;
    }

    public String getEdgeId() {
        return edgeId;
    }
}
