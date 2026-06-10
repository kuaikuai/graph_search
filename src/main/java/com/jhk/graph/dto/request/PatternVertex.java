package com.jhk.graph.dto.request;

import java.util.Map;

/**
 * Pattern query vertex definition.
 * Used in pattern queries to define nodes with id, type and filters.
 */
public class PatternVertex {
    private String id;           // unique vertex identifier for edge references
    private String type;         // vertex type/tag
    private Map<String, Object> filters;  // property filters

    public PatternVertex() {}

    public PatternVertex(String id, String type, Map<String, Object> filters) {
        this.id = id;
        this.type = type;
        this.filters = filters;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Map<String, Object> getFilters() { return filters; }
    public void setFilters(Map<String, Object> filters) { this.filters = filters; }

    public boolean hasFilters() {
        return filters != null && !filters.isEmpty();
    }
}