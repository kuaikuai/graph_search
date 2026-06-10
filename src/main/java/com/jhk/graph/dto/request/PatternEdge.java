package com.jhk.graph.dto.request;

import java.util.Map;

/**
 * Pattern query edge definition.
 * Used in pattern queries to define edges between nodes.
 */
public class PatternEdge {
    private String from;          // source node id
    private String to;            // target node id
    private String label;         // edge type (null = any)
    private String direction;     // out | in | both (default: out)
    private Integer minHops;      // minimum hops for variable length paths
    private Integer maxHops;      // maximum hops for variable length paths
    private Map<String, Object> filters;  // edge property filters

    public PatternEdge() {}

    public PatternEdge(String from, String to, String label) {
        this.from = from;
        this.to = to;
        this.label = label;
    }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public Integer getMinHops() { return minHops; }
    public void setMinHops(Integer minHops) { this.minHops = minHops; }

    public Integer getMaxHops() { return maxHops; }
    public void setMaxHops(Integer maxHops) { this.maxHops = maxHops; }

    public Map<String, Object> getFilters() { return filters; }
    public void setFilters(Map<String, Object> filters) { this.filters = filters; }

    public boolean hasFilters() {
        return filters != null && !filters.isEmpty();
    }

    public boolean isVariableHops() {
        return minHops != null || maxHops != null;
    }

    public String getEffectiveDirection() {
        return direction != null ? direction : "out";
    }
}