package porto.sweep.io;

import porto.sweep.model.EdgeStats;
import porto.sweep.model.GraphEdge;
import porto.sweep.model.RouteData;
import porto.sweep.model.StableRouteRef;
import porto.sweep.util.PipeSeq;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DataRepository {
    private final Map<String, List<RouteData>> candidateRoutesByOd = new LinkedHashMap<>();
    private final Map<String, List<StableRouteRef>> stableRoutesByOd = new LinkedHashMap<>();
    private final Map<String, EdgeStats> edgeStatsById = new LinkedHashMap<>();
    private final Map<String, List<GraphEdge>> globalGraph = new LinkedHashMap<>();
    private final Map<String, Map<String, List<GraphEdge>>> odGraphs = new LinkedHashMap<>();
    private final Map<String, Map<String, double[]>> nodeCoordsByRoute = new HashMap<>();
    private final Map<String, Integer> odTripCount = new LinkedHashMap<>();
    private final Map<String, List<GraphEdge>> rerouteTimeGraph = new LinkedHashMap<>();
    // Reverse adjacency for bidirectional reroute search. Lists reference the same
    // GraphEdge objects as rerouteTimeGraph, so edge objects are not duplicated.
    private final Map<String, List<GraphEdge>> rerouteTimeReverseGraph = new HashMap<>();
    private final Map<String, List<GraphEdge>> rerouteLengthGraph = new LinkedHashMap<>();
    private final Map<String, EdgeStats> rerouteEdgeStatsById = new LinkedHashMap<>();
    private final Map<String, double[]> backgroundTrafficByEdgeHour = new LinkedHashMap<>();


    public Map<String, List<GraphEdge>> getRerouteTimeGraph() {
        return rerouteTimeGraph;
    }

    public Map<String, List<GraphEdge>> getRerouteTimeReverseGraph() {
        return rerouteTimeReverseGraph;
    }

    public Map<String, List<GraphEdge>> getRerouteLengthGraph() {
        return rerouteLengthGraph;
    }

    public static DataRepository load(Path candidateRoutesPath, Path routeWaypointsPath,
                                      Path edgeLookupPath, Path stableRoutesPath,
                                      Path rerouteTimeGraphCsvPath, Path rerouteLengthGraphCsvPath) throws IOException {
        return load(candidateRoutesPath, routeWaypointsPath, edgeLookupPath, stableRoutesPath,
                rerouteTimeGraphCsvPath, rerouteLengthGraphCsvPath, null);
    }

    public static DataRepository load(Path candidateRoutesPath, Path routeWaypointsPath,
                                      Path edgeLookupPath, Path stableRoutesPath,
                                      Path rerouteTimeGraphCsvPath, Path rerouteLengthGraphCsvPath,
                                      Path backgroundTrafficCsvPath) throws IOException {
        DataRepository repo = new DataRepository();
        repo.loadCandidateRoutes(candidateRoutesPath);
        // Route waypoint coordinates are not used by the simulation engine.
        // Skipping this potentially huge file avoids retaining millions of coordinates.
        repo.loadEdgeLookup(edgeLookupPath);
        repo.loadStableRoutes(stableRoutesPath);
        repo.loadRerouteGraphCsv(rerouteTimeGraphCsvPath, repo.rerouteTimeGraph);
        repo.buildReverseGraph(repo.rerouteTimeGraph, repo.rerouteTimeReverseGraph);
        repo.loadRerouteGraphCsv(rerouteLengthGraphCsvPath, repo.rerouteLengthGraph);
        if (backgroundTrafficCsvPath != null) {
            repo.loadBackgroundTrafficCsv(backgroundTrafficCsvPath);
        }
        repo.buildGraphs();
        return repo;
    }
    private void loadRerouteGraphCsv(Path path, Map<String, List<GraphEdge>> targetGraph) throws IOException {
        SimpleCsv.forEach(path, row -> {
            String from = firstNonBlank(row, "from_node");
            String to = firstNonBlank(row, "to_node");
            String edgeId = firstNonBlank(row, "edge_id");

            if (from.isEmpty() || to.isEmpty() || edgeId.isEmpty()) {
                return;
            }

            targetGraph.computeIfAbsent(from, k -> new ArrayList<>())
                    .add(new GraphEdge(from, to, edgeId));
        });
    }

    private void buildReverseGraph(Map<String, List<GraphEdge>> forward,
                                   Map<String, List<GraphEdge>> reverse) {
        reverse.clear();
        for (List<GraphEdge> edges : forward.values()) {
            for (GraphEdge edge : edges) {
                reverse.computeIfAbsent(edge.getToNode(), k -> new ArrayList<>()).add(edge);
            }
        }
    }

    public Map<String, List<RouteData>> getCandidateRoutesByOd() {
        return candidateRoutesByOd;
    }

    public Map<String, List<StableRouteRef>> getStableRoutesByOd() {
        return stableRoutesByOd;
    }

    public Map<String, EdgeStats> getEdgeStatsById() {
        return edgeStatsById;
    }

    public Map<String, List<GraphEdge>> getGlobalGraph() {
        return globalGraph;
    }

    public Map<String, List<GraphEdge>> getOdGraph(String odId) {
        return odGraphs.getOrDefault(odId, Collections.emptyMap());
    }

    public Map<String, Integer> getOdTripCount() {
        return odTripCount;
    }

    public double getBackgroundTrafficFlow(String edgeId, int hour) {
        if (edgeId == null || edgeId.isBlank()) {
            return 0.0;
        }
        double[] hourly = backgroundTrafficByEdgeHour.get(edgeId);
        if (hourly == null || hourly.length == 0) {
            return 0.0;
        }
        int h = ((hour % 24) + 24) % 24;
        return hourly[h];
    }

    public int getBackgroundTrafficEdgeCount() {
        return backgroundTrafficByEdgeHour.size();
    }

    public int getBackgroundTrafficCellCount() {
        int n = 0;
        for (double[] hourly : backgroundTrafficByEdgeHour.values()) {
            for (double v : hourly) {
                if (v != 0.0) {
                    n++;
                }
            }
        }
        return n;
    }

    public String loadSummary() {
        return "Repository summary:"
                + System.lineSeparator() + "  candidate_od_count=" + candidateRoutesByOd.size()
                + System.lineSeparator() + "  candidate_route_count=" + countRoutes(candidateRoutesByOd)
                + System.lineSeparator() + "  stable_od_count=" + stableRoutesByOd.size()
                + System.lineSeparator() + "  stable_route_count=" + countRoutes(stableRoutesByOd)
                + System.lineSeparator() + "  edge_stats_count=" + edgeStatsById.size()
                + System.lineSeparator() + "  global_graph_node_count=" + globalGraph.size()
                + System.lineSeparator() + "  global_graph_edge_count=" + countGraphEdges(globalGraph)
                + System.lineSeparator() + "  reroute_time_graph_edge_count=" + countGraphEdges(rerouteTimeGraph)
                + System.lineSeparator() + "  reroute_length_graph_edge_count=" + countGraphEdges(rerouteLengthGraph)
                + System.lineSeparator() + "  background_traffic_edge_count=" + getBackgroundTrafficEdgeCount()
                + System.lineSeparator() + "  background_traffic_hour_cells=" + getBackgroundTrafficCellCount();
    }

    private static int countRoutes(Map<?, ? extends List<?>> byOd) {
        int n = 0;
        for (List<?> routes : byOd.values()) {
            n += routes.size();
        }
        return n;
    }

    private static int countGraphEdges(Map<String, List<GraphEdge>> graph) {
        int n = 0;
        for (List<GraphEdge> edges : graph.values()) {
            n += edges.size();
        }
        return n;
    }

    public double[] getNodeCoord(String routeId, String nodeId) {
        Map<String, double[]> inner = nodeCoordsByRoute.get(routeId);
        if (inner == null) {
            return null;
        }
        return inner.get(nodeId);
    }

    private void loadCandidateRoutes(Path path) throws IOException {
        SimpleCsv.forEach(path, row -> {
            String odId = firstNonBlank(row, "od_id");
            String routeId = firstNonBlank(row, "route_id");
            List<String> nodeSeq = PipeSeq.split(firstNonBlank(row, "route_node_seq", "sample_route_node_seq", "nodes"));
            List<String> edgeSeq = PipeSeq.split(firstNonBlank(row, "edge_seq", "sample_edge_seq"));
            if (edgeSeq.isEmpty() && nodeSeq.size() >= 2) {
                edgeSeq = syntheticEdgeSeq(nodeSeq);
            }
            if (odId.isEmpty() || routeId.isEmpty() || nodeSeq.size() < 2 || edgeSeq.isEmpty()) {
                return;
            }
            if (edgeSeq.size() != nodeSeq.size() - 1) {
                edgeSeq = reconcileEdgeSeq(nodeSeq, edgeSeq);
            }
            RouteData route = new RouteData(
                    odId,
                    routeId,
                    parseDouble(firstNonBlank(row, "stable_share_norm", "share"), 0.0),
                    parseDouble(firstNonBlank(row, "avg_time_sec"), 0.0),
                    parseDouble(firstNonBlank(row, "avg_distance_m"), 0.0),
                    (int) parseDouble(firstNonBlank(row, "route_rank"), 999999),
                    nodeSeq,
                    edgeSeq
            );
            candidateRoutesByOd.computeIfAbsent(odId, k -> new ArrayList<>()).add(route);
        });
        for (List<RouteData> list : candidateRoutesByOd.values()) {
            list.sort(Comparator.comparingInt(RouteData::getRouteRank).thenComparing(RouteData::getRouteId));
        }
    }

    private void loadWaypoints(Path path) throws IOException {
        SimpleCsv.forEach(path, row -> {
            String routeId = firstNonBlank(row, "route_id");
            String nodeId = firstNonBlank(row, "node_id");
            double lon = parseDouble(firstNonBlank(row, "lon"), Double.NaN);
            double lat = parseDouble(firstNonBlank(row, "lat"), Double.NaN);
            if (routeId.isEmpty() || nodeId.isEmpty() || Double.isNaN(lon) || Double.isNaN(lat)) {
                return;
            }
            nodeCoordsByRoute.computeIfAbsent(routeId, k -> new LinkedHashMap<>()).put(nodeId, new double[]{lon, lat});
        });
    }

    private void loadEdgeLookup(Path path) throws IOException {
        SimpleCsv.forEach(path, row -> {
            String edgeId = firstNonBlank(row, "edge_id");
            if (edgeId.isEmpty()) {
                return;
            }
            double mean = parseDouble(firstNonBlank(row, "mean_traversal_time_sec"), 30.0);
            double count = parseDouble(firstNonBlank(row, "edge_traversal_count", "count"), 10.0);
            EdgeStats stats = edgeStatsById.computeIfAbsent(edgeId, k -> new EdgeStats(edgeId, mean, count));
            String hourKey = firstNonBlank(row, "hour_of_day_str", "hour_bucket", "hour");
            if (!hourKey.isEmpty()) {
                stats.putHourMean(hourKey, mean);
            }
        });
    }

    private void loadBackgroundTrafficCsv(Path path) throws IOException {
        SimpleCsv.forEach(path, row -> {
            String edgeId = firstNonBlank(row, "edge_id", "edge");
            if (edgeId.isEmpty()) {
                return;
            }

            double flow = parseDouble(firstNonBlank(row,
                    "background_flow",
                    "backgroundFlow",
                    "flow",
                    "edge_flow",
                    "count",
                    "avg_flow"
            ), Double.NaN);
            if (!Double.isFinite(flow) || flow < 0.0) {
                return;
            }

            String hourRaw = firstNonBlank(row, "hour_of_day", "hour", "hour_bucket", "hour_of_day_str");
            double[] hourly = backgroundTrafficByEdgeHour.computeIfAbsent(edgeId, k -> new double[24]);

            if (hourRaw.isEmpty() || hourRaw.equalsIgnoreCase("all") || hourRaw.equalsIgnoreCase("all_day")) {
                for (int h = 0; h < 24; h++) {
                    hourly[h] += flow;
                }
            } else {
                int hour = parseHour(hourRaw);
                if (hour >= 0 && hour < 24) {
                    hourly[hour] += flow;
                }
            }
        });
    }

    private void loadStableRoutes(Path path) throws IOException {
        SimpleCsv.forEach(path, row -> {
            String odId = firstNonBlank(row, "od_id");
            String routeId = firstNonBlank(row, "route_id");
            List<String> nodeSeq = PipeSeq.split(firstNonBlank(row, "sample_route_node_seq", "route_node_seq", "nodes"));
            List<String> edgeSeq = PipeSeq.split(firstNonBlank(row, "sample_edge_seq", "edge_seq", "edge_set"));
            if (edgeSeq.isEmpty() && nodeSeq.size() >= 2) {
                edgeSeq = syntheticEdgeSeq(nodeSeq);
            }
            if (odId.isEmpty() || routeId.isEmpty() || edgeSeq.isEmpty()) {
                return;
            }
            if (!nodeSeq.isEmpty() && edgeSeq.size() != nodeSeq.size() - 1) {
                edgeSeq = reconcileEdgeSeq(nodeSeq, edgeSeq);
            }
            int count = (int) parseDouble(firstNonBlank(row, "od_trip_count", "kept_trip_total", "trip_count"), 0.0);
            StableRouteRef ref = new StableRouteRef(
                    odId,
                    routeId,
                    parseDouble(firstNonBlank(row, "stable_share_norm", "share"), 0.0),
                    parseDouble(firstNonBlank(row, "avg_time_sec"), Double.NaN),
                    count,
                    nodeSeq,
                    edgeSeq
            );
            stableRoutesByOd.computeIfAbsent(odId, k -> new ArrayList<>()).add(ref);
            odTripCount.putIfAbsent(odId, count);
        });
        for (List<StableRouteRef> list : stableRoutesByOd.values()) {
            list.sort(Comparator.comparing(StableRouteRef::getRouteId));
        }
    }

    private void buildGraphs() {
        for (Map.Entry<String, List<RouteData>> entry : candidateRoutesByOd.entrySet()) {
            String odId = entry.getKey();
            Map<String, List<GraphEdge>> odGraph = new LinkedHashMap<>();
            for (RouteData route : entry.getValue()) {
                addRouteToGraph(route, odGraph);
                addRouteToGraph(route, globalGraph);
            }
            odGraphs.put(odId, odGraph);
        }
    }

    private void addRouteToGraph(RouteData route, Map<String, List<GraphEdge>> graph) {
        List<String> nodes = route.getNodeSeq();
        List<String> edges = route.getEdgeSeq();
        for (int i = 0; i < Math.min(nodes.size() - 1, edges.size()); i++) {
            String from = nodes.get(i);
            String to = nodes.get(i + 1);
            String edgeId = edges.get(i);
            graph.computeIfAbsent(from, k -> new ArrayList<>()).add(new GraphEdge(from, to, edgeId));
        }
    }

    private static List<String> syntheticEdgeSeq(List<String> nodeSeq) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < nodeSeq.size() - 1; i++) {
            out.add(nodeSeq.get(i) + "->" + nodeSeq.get(i + 1));
        }
        return out;
    }

    private static List<String> reconcileEdgeSeq(List<String> nodeSeq, List<String> edgeSeq) {
        List<String> out = new ArrayList<>();
        int needed = Math.max(0, nodeSeq.size() - 1);
        for (int i = 0; i < needed; i++) {
            if (i < edgeSeq.size() && !edgeSeq.get(i).isBlank()) {
                out.add(edgeSeq.get(i));
            } else {
                out.add(nodeSeq.get(i) + "->" + nodeSeq.get(i + 1));
            }
        }
        return out;
    }

    private static String firstNonBlank(Map<String, String> row, String... keys) {
        for (String k : keys) {
            String v = row.get(k);
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }

    private static int parseHour(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return -1;
        }
        String s = raw.trim();
        if (s.length() >= 2 && Character.isDigit(s.charAt(0)) && Character.isDigit(s.charAt(1))) {
            try {
                return Integer.parseInt(s.substring(0, 2));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        try {
            return (int) Math.floor(Double.parseDouble(s));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
