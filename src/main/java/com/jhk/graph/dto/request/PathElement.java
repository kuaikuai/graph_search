package com.jhk.graph.dto.request;

import java.util.Map;

/**
 * pattern 查询的路径元素
 * 交替表示节点 {type, filters, as} 和边 {edge, as}
 */
public class PathElement {

    // 节点字段
    private String type;
    private String as;
    private Map<String, Object> filters;

    // 边字段
    private String edge;

    public PathElement() {}

    // 节点构造函数
    public PathElement(String type, String as, Map<String, Object> filters) {
        this.type = type;
        this.as = as;
        this.filters = filters;
    }

    // 边构造函数
    public static PathElement edge(String edge, String as) {
        PathElement e = new PathElement();
        e.edge = edge;
        e.as = as;
        return e;
    }

    // 节点构造函数
    public static PathElement node(String type, String as, Map<String, Object> filters) {
        return new PathElement(type, as, filters);
    }

    public boolean isEdge() { return edge != null; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getAs() { return as; }
    public void setAs(String as) { this.as = as; }

    public Map<String, Object> getFilters() { return filters; }
    public void setFilters(Map<String, Object> filters) { this.filters = filters; }

    public String getEdge() { return edge; }
    public void setEdge(String edge) { this.edge = edge; }
}
