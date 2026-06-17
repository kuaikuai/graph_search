package com.jhk.graph.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Pattern query vertex definition.
 * Used in pattern queries to define nodes with alias, type and filters.
 */
public class PatternVertex {
    private String as;           // unique vertex alias for edge references (from/to use this)
    private String type;         // vertex type/tag
    private Map<String, Object> filters;  // property filters

    public PatternVertex() {}

    public PatternVertex(String as, String type, Map<String, Object> filters) {
        this.as = as;
        this.type = type;
        this.filters = filters;
    }

    @JsonProperty("as")
    public String getAs() { return as; }

    @JsonProperty("as")
    public void setAs(String as) { this.as = as; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Map<String, Object> getFilters() { return filters; }
    public void setFilters(Map<String, Object> filters) { this.filters = filters; }

    public boolean hasFilters() {
        return filters != null && !filters.isEmpty();
    }
}