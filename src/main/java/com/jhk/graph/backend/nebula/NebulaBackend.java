package com.jhk.graph.backend.nebula;

import com.jhk.graph.backend.GraphBackend;
import com.jhk.graph.config.BackendConfig.NebulaProperties;
import com.jhk.graph.dto.request.GraphQueryRequest;
import com.jhk.graph.dto.response.GraphQueryResponse;
import com.jhk.graph.exception.GraphQueryException;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.data.ValueWrapper;
import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NebulaGraph 后端实现
 * 将统一 JSON 请求转换为 nGQL 查询并执行
 */
public class NebulaBackend implements GraphBackend {

    private final NebulaConnection connection;
    private final NgqlConverter converter;

    public NebulaBackend(NebulaProperties config) {
        try {
            this.connection = new NebulaConnection(config);
        } catch (UnknownHostException e) {
            throw new GraphQueryException("BACKEND_ERROR", "Failed to connect to NebulaGraph: " + e.getMessage(), e);
        }
        this.converter = new NgqlConverter();
    }

    @Override
    public GraphQueryResponse execute(GraphQueryRequest request) {
        String ngql = converter.toNgql(request);
        String resultScope = request.getResultScope() != null ? request.getResultScope() : "paths";
        return executeNgql(ngql, request.getQueryType(), resultScope);
    }

    @Override
    public String getType() {
        return "nebula";
    }

    @Override
    public String buildSparql(GraphQueryRequest request) {
        // For nebula, we return nGQL instead of SPARQL
        return converter.toNgql(request);
    }

    private GraphQueryResponse executeNgql(String ngql, String queryType, String resultScope) {
        try {
            // SessionPool handles: authentication, USE space, connection retry automatically
            ResultSet rs = connection.execute(ngql);
            if (!rs.isSucceeded()) {
                String errorMsg = rs.getErrorMessage();
                throw new GraphQueryException("BACKEND_ERROR", "nGQL execution failed: " + errorMsg);
            }
            return parseResult(rs, queryType, resultScope);
        } catch (GraphQueryException e) {
            throw e;
        } catch (Exception e) {
            throw new GraphQueryException("BACKEND_ERROR", "nGQL execution failed: " + e.getMessage(), e);
        }
    }

    private GraphQueryResponse parseResult(ResultSet rs, String queryType, String resultScope) {
        GraphQueryResponse response = new GraphQueryResponse();
        response.setQueryType(queryType);

        int rows = rs.rowsSize();

        // Check if this is a multi-path pattern result (multiple columns, all Path values)
        boolean isMultiPath = false;
        List<String> columnNames = null;
        if (rows > 0 && "pattern".equals(queryType)) {
            try {
                ResultSet.Record record = rs.rowValues(0);
                List<ValueWrapper> values = record.values();
                if (values.size() >= 2) {
                    boolean allPaths = true;
                    for (ValueWrapper v : values) {
                        if (!v.isPath()) { allPaths = false; break; }
                    }
                    if (allPaths) {
                        isMultiPath = true;
                        columnNames = rs.keys();
                    }
                }
            } catch (Exception e) {
                // Fall through to single-path logic
            }
        }

        if (isMultiPath) {
            // Multi-path pattern: RETURN p1, p2, ... → flatten into paths with pathType
            List<GraphQueryResponse.PathResult> flatPaths = new ArrayList<>();

            for (int i = 0; i < rows; i++) {
                try {
                    ResultSet.Record record = rs.rowValues(i);
                    List<ValueWrapper> values = record.values();

                    for (int j = 0; j < values.size() && j < columnNames.size(); j++) {
                        GraphQueryResponse.PathResult path = parseColumnAsPath(values.get(j));
                        path.setPathType(columnNames.get(j));
                        flatPaths.add(path);
                    }
                } catch (Exception e) {
                    // Skip malformed rows
                }
            }

            response.setPaths(flatPaths);
            response.setTotalPaths(flatPaths.size());

        } else {
            // Single-path or non-pattern: existing logic
            List<GraphQueryResponse.PathResult> paths = new ArrayList<>();
            List<GraphQueryResponse.NodeResult> allNodes = new ArrayList<>();

            for (int i = 0; i < rows; i++) {
                GraphQueryResponse.PathResult path = parsePathResult(rs, i);
                paths.add(path);

                if ("nodes".equals(resultScope)) {
                    for (GraphQueryResponse.NodeResult node : path.getNodes()) {
                        allNodes.add(node);
                    }
                }
            }

            if ("nodes".equals(resultScope)) {
                Map<String, List<String>> byHop = new HashMap<>();
                for (int i = 0; i < paths.size(); i++) {
                    GraphQueryResponse.PathResult path = paths.get(i);
                    int hop = path.getTotalHops();
                    String hopKey = String.valueOf(hop);
                    byHop.computeIfAbsent(hopKey, k -> new ArrayList<>());
                    if (!path.getNodes().isEmpty()) {
                        GraphQueryResponse.NodeResult destNode = path.getNodes().get(path.getNodes().size() - 1);
                        byHop.get(hopKey).add(destNode.get("id").toString());
                    }
                }
                response.setByHop(byHop);
                response.setNodes(allNodes);
            } else {
                response.setPaths(paths);
                response.setTotalPaths(paths.size());
            }
        }

        return response;
    }


    /**
     * Parse a single ValueWrapper (Path) into a PathResult
     */
    private GraphQueryResponse.PathResult parseColumnAsPath(ValueWrapper valueWrapper) {
        GraphQueryResponse.PathResult path = new GraphQueryResponse.PathResult();
        List<GraphQueryResponse.NodeResult> pathNodes = new ArrayList<>();
        List<GraphQueryResponse.EdgeResult> pathEdges = new ArrayList<>();
        try {
            if (valueWrapper.isPath()) {
                var pathObj = valueWrapper.asPath();
                for (var node : pathObj.getNodes()) {
                    String nodeId = extractVid(node.getId());
                    String type = node.tagNames().isEmpty() ? "Unknown" : node.tagNames().get(0);
                    pathNodes.add(new GraphQueryResponse.NodeResult(nodeId, type, parseNodeProperties(node, type)));
                }
                for (var edge : pathObj.getRelationships()) {
                    String from = extractVid(edge.srcId());
                    String to = extractVid(edge.dstId());
                    pathEdges.add(new GraphQueryResponse.EdgeResult(null, from, to, edge.edgeName(), parseEdgeProperties(edge)));
                }
            }
        } catch (Exception e) { /* Return empty path on parse failure */ }
        path.setNodes(pathNodes);
        path.setEdges(pathEdges);
        path.setTotalHops(pathEdges.size());
        path.setDescription(buildDescription(pathNodes, pathEdges));
        return path;
    }

    /**
     * Parse a ResultSet row into a PathResult (single-path / legacy)
     */
    private GraphQueryResponse.PathResult parsePathResult(ResultSet rs, int rowIndex) {
        try {
            ResultSet.Record record = rs.rowValues(rowIndex);
            List<ValueWrapper> values = record.values();
            if (values.size() == 1 && values.get(0).isPath()) {
                return parseColumnAsPath(values.get(0));
            }
            GraphQueryResponse.PathResult path = new GraphQueryResponse.PathResult();
            List<GraphQueryResponse.NodeResult> pathNodes = new ArrayList<>();
            List<GraphQueryResponse.EdgeResult> pathEdges = new ArrayList<>();
            for (ValueWrapper v : values) {
                if (v.isVertex()) {
                    var node = v.asNode();
                    pathNodes.add(new GraphQueryResponse.NodeResult(extractVid(node.getId()),
                        node.tagNames().isEmpty() ? "Unknown" : node.tagNames().get(0),
                        parseNodeProperties(node, node.tagNames().isEmpty() ? "" : node.tagNames().get(0))));
                } else if (v.isEdge()) {
                    var edge = v.asRelationship();
                    pathEdges.add(new GraphQueryResponse.EdgeResult(null,
                        extractVid(edge.srcId()), extractVid(edge.dstId()),
                        edge.edgeName(), parseEdgeProperties(edge)));
                }
            }
            path.setNodes(pathNodes);
            path.setEdges(pathEdges);
            path.setTotalHops(pathEdges.size());
            path.setDescription(buildDescription(pathNodes, pathEdges));
            return path;
        } catch (Exception e) {
            GraphQueryResponse.PathResult fallback = new GraphQueryResponse.PathResult();
            fallback.setNodes(new ArrayList<>());
            fallback.setEdges(new ArrayList<>());
            fallback.setTotalHops(0);
            fallback.setDescription("");
            return fallback;
        }
    }

    private String extractVid(ValueWrapper vidWrapper) {
        try {
            if (vidWrapper.isString()) {
                return vidWrapper.asString();
            } else if (vidWrapper.isLong()) {
                return String.valueOf(vidWrapper.asLong());
            }
            return vidWrapper.toString();
        } catch (UnsupportedEncodingException e) {
            return vidWrapper.toString();
        }
    }

    private Map<String, Object> parseNodeProperties(com.vesoft.nebula.client.graph.data.Node node, String tagName) {
        Map<String, Object> props = new HashMap<>();
        try {
            var properties = node.properties(tagName);
            if (properties != null) {
                for (var prop : properties.entrySet()) {
                    props.put(prop.getKey(), extractValue(prop.getValue()));
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return props;
    }

    private Map<String, Object> parseEdgeProperties(com.vesoft.nebula.client.graph.data.Relationship edge) {
        Map<String, Object> props = new HashMap<>();
        try {
            var properties = edge.properties();
            if (properties != null) {
                for (var prop : properties.entrySet()) {
                    props.put(prop.getKey(), extractValue(prop.getValue()));
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return props;
    }

    private Object extractValue(ValueWrapper wrapper) {
        try {
            if (wrapper.isString()) return wrapper.asString();
            if (wrapper.isLong()) return wrapper.asLong();
            if (wrapper.isDouble()) return wrapper.asDouble();
            if (wrapper.isBoolean()) return wrapper.asBoolean();
            return wrapper.toString();
} catch (UnsupportedEncodingException e) {
            return wrapper.toString();
        }
    }

    private String inferType(String nodeId) {
        // NebulaGraph doesn't have a direct type inference mechanism like RDF
        // Return "Unknown" or try to infer from node id pattern
        if (nodeId == null || nodeId.isBlank()) {
            return "Unknown";
        }
        // Simple heuristic: if ID contains type prefix, extract it
        // Otherwise default to "Unknown"
        return "Unknown";
    }

    private static String buildDescription(List<GraphQueryResponse.NodeResult> nodes, List<GraphQueryResponse.EdgeResult> edges) {
        if (nodes == null || nodes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            GraphQueryResponse.NodeResult node = nodes.get(i);
            String type = (String) node.getOrDefault("type", "?");
            String name = extractDisplayName(node);
            sb.append(type).append("(").append(name).append(")");
            if (i < edges.size()) {
                String label = (String) edges.get(i).getOrDefault("label", "?");
                sb.append(" -> ").append(label).append(" -> ");
            }
        }
        return sb.toString();
    }

    private static String extractDisplayName(GraphQueryResponse.NodeResult node) {
        // Prefer "name" property if exists
        Object name = node.get("name");
        if (name != null) {
            return name.toString();
        }
        // Fall back to id
        Object id = node.get("id");
        return id != null ? id.toString() : "?";
    }
}