package com.jhk.graph.dto.request;

import java.util.Map;

public class GraphQueryRequest {

    private String queryType;   // path | traverse | pattern
    private SourceTarget target; // target node definition (path/traverse)
    private SourceTarget source; // source node definition (path/traverse)
    private String mode;        // shortest | all (path)
    private String[] edgeLabels;
    private Integer maxHops;
    private Integer minHops = 1;  // default 1
    private String direction;   // out | in | both (traverse)
    private String resultScope;  // nodes | paths (traverse)
    private PathElement[] path;  // pattern (deprecated, use nodes + edges)
    private PatternVertex[] nodes;  // pattern nodes
    private PatternEdge[] edges;       // pattern edges
    private String where;
    private String[] select;
    private String[] targetProperties;  // properties to fetch for target node (path query)
    private Boolean dryRun;     // if true, only return generated SPARQL without executing
    private Integer limit = 10;  // result limit, default 10

    public String getQueryType() { return queryType; }
    public void setQueryType(String queryType) { this.queryType = queryType; }

    /**
     * Get target as SourceTarget object (for structured input).
     */
    public SourceTarget getTargetObj() { return target; }

    /**
     * Get target as plain string (for backward compatibility).
     * Returns the type string if target is a SourceTarget, otherwise the raw string.
     */
    public String getTarget() {
        if (target != null) return target.getType();
        return null;
    }

    public void setTarget(Object target) { this.target = parseSourceTarget(target); }

    /**
     * Get source as SourceTarget object (for structured input).
     */
    public SourceTarget getSourceObj() { return source; }

    /**
     * Get source as plain string (for backward compatibility).
     * Returns the type string if source is a SourceTarget, otherwise the raw string.
     */
    public String getSource() {
        if (source != null) return source.getType();
        return null;
    }

    public void setSource(Object source) { this.source = parseSourceTarget(source); }

    /**
     * Parse a source/target value from request JSON.
     * Accepts:
     *   - String: "PainPoint" (plain type name)
     *   - Object: {"type": "Person", "id": "optional-id", "filters": {...}}
     *   - SourceTarget: already parsed object (returned as-is)
     */
    @SuppressWarnings("unchecked")
    private static SourceTarget parseSourceTarget(Object val) {
        if (val == null) return null;
        if (val instanceof SourceTarget) {
            // Already a SourceTarget instance - return as-is
            return (SourceTarget) val;
        }
        if (val instanceof String) {
            // Plain string = just a type name
            SourceTarget st = new SourceTarget();
            st.setType((String) val);
            return st;
        }
        if (val instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) val;
            SourceTarget st = new SourceTarget();
            st.setType((String) map.get("type"));
            st.setId((String) map.get("id"));
            Object filters = map.get("filters");
            if (filters instanceof Map) {
                st.setFilters((Map<String, Object>) filters);
            }
            return st;
        }
        // Fallback
        SourceTarget st = new SourceTarget();
        st.setType(val.toString());
        return st;
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String[] getEdgeLabels() { return edgeLabels; }
    public void setEdgeLabels(String[] edgeLabels) { this.edgeLabels = edgeLabels; }

    public Integer getMaxHops() { return maxHops; }
    public void setMaxHops(Integer maxHops) { this.maxHops = maxHops; }

    public Integer getMinHops() { return minHops; }
    public void setMinHops(Integer minHops) { this.minHops = minHops; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getResultScope() { return resultScope; }
    public void setResultScope(String resultScope) { this.resultScope = resultScope; }

    public PathElement[] getPath() { return path; }
    public void setPath(PathElement[] path) { this.path = path; }

    public PatternVertex[] getNodes() { return nodes; }
    public void setNodes(PatternVertex[] nodes) { this.nodes = nodes; }

    public PatternEdge[] getEdges() { return edges; }
    public void setEdges(PatternEdge[] edges) { this.edges = edges; }

    public String getWhere() { return where; }
    public void setWhere(String where) { this.where = where; }

    public String[] getSelect() { return select; }
    public void setSelect(String[] select) { this.select = select; }

    public String[] getTargetProperties() { return targetProperties; }
    public void setTargetProperties(String[] targetProperties) { this.targetProperties = targetProperties; }

    public Boolean getDryRun() { return dryRun; }
    public void setDryRun(Boolean dryRun) { this.dryRun = dryRun; }

    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }

    // ==================== Inner class: SourceTarget ====================

    /**
     * Represents a node constraint: type + optional id/filters
     * Used for source/target in path and traverse queries.
     */
    public static class SourceTarget {
        private String type;                  // e.g., "Organization", "PainPoint"
        private String id;                    // optional direct instance URI
        private Map<String, Object> filters; // e.g., {"name": "研发部"}

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public Map<String, Object> getFilters() { return filters; }
        public void setFilters(Map<String, Object> filters) { this.filters = filters; }

        /** Whether this source/target has filters that need resolution */
        public boolean hasFilters() {
            return filters != null && !filters.isEmpty();
        }

        /** Whether this has a direct instance URI */
        public boolean hasId() {
            return id != null && !id.isBlank();
        }
    }
}
