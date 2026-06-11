package com.jhk.graph.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图查询响应 DTO
 * data 字段的内容，根据 queryType 不同而不同
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphQueryResponse {

    private String queryType;
    private List<PathResult> paths;
    private Integer totalPaths;
    private List<NodeResult> nodes;
    private Map<String, List<String>> byHop;
    private Integer totalResults;

    // getters & setters
    public String getQueryType() { return queryType; }
    public void setQueryType(String queryType) { this.queryType = queryType; }

    public List<PathResult> getPaths() { return paths; }
    public void setPaths(List<PathResult> paths) { this.paths = paths; }

    public Integer getTotalPaths() { return totalPaths; }
    public void setTotalPaths(Integer totalPaths) { this.totalPaths = totalPaths; }

    public List<NodeResult> getNodes() { return nodes; }
    public void setNodes(List<NodeResult> nodes) { this.nodes = nodes; }

    public Map<String, List<String>> getByHop() { return byHop; }
    public void setByHop(Map<String, List<String>> byHop) { this.byHop = byHop; }

    public Integer getTotalResults() { return totalResults; }
    public void setTotalResults(Integer totalResults) { this.totalResults = totalResults; }

    /**
     * 路径结果 (path 查询)
     */
    public static class PathResult {
        private List<NodeResult> nodes;
        private List<EdgeResult> edges;
        private int totalHops;
        private String description;
        private String pathType;  // multi-path pattern: which path variable (p1, p2, ...)

        public List<NodeResult> getNodes() { return nodes; }
        public void setNodes(List<NodeResult> nodes) { this.nodes = nodes; }

        public List<EdgeResult> getEdges() { return edges; }
        public void setEdges(List<EdgeResult> edges) { this.edges = edges; }

        public int getTotalHops() { return totalHops; }
        public void setTotalHops(int totalHops) { this.totalHops = totalHops; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getPathType() { return pathType; }
        public void setPathType(String pathType) { this.pathType = pathType; }
    }

    /**
     * 节点结果 - 属性平铺
     */
    public static class NodeResult extends LinkedHashMap<String, Object> {
        private static final String ID_KEY = "id";
        private static final String TYPE_KEY = "type";

        public NodeResult() {}

        public NodeResult(String id, String type, Map<String, Object> properties) {
            super();
            put(ID_KEY, id);
            put(TYPE_KEY, type);
            if (properties != null) {
                putAll(properties);
            }
        }
    }

    /**
     * 边结果 - 属性平铺
     */
    public static class EdgeResult extends LinkedHashMap<String, Object> {
        private static final String ID_KEY = "id";
        private static final String FROM_KEY = "from";
        private static final String TO_KEY = "to";
        private static final String LABEL_KEY = "label";

        public EdgeResult() {}

        public EdgeResult(String id, String from, String to, String label, Map<String, Object> properties) {
            super();
            if (id != null) put(ID_KEY, id);
            put(FROM_KEY, from);
            put(TO_KEY, to);
            put(LABEL_KEY, label);
            if (properties != null) {
                putAll(properties);
            }
        }
    }
}
