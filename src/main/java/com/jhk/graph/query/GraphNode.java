package com.jhk.graph.query;

import org.apache.jena.rdf.model.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 路径子图中的节点
 * @param cls      当前节点类
 * @param property 到达此节点的属性（从父节点出发的属性）
 * @param children 子节点列表（分支）
 * @param isAnchor 此节点是否为锚点
 */
public record GraphNode(
    Resource cls,
    Resource property,
    List<GraphNode> children,
    boolean isAnchor
) {
    public GraphNode(Resource cls, Resource property, List<GraphNode> children) {
        this(cls, property, children, false);
    }

    public GraphNode withIsAnchor(boolean isAnchor) {
        return new GraphNode(this.cls, this.property, this.children, isAnchor);
    }

    public GraphNode withChildren(List<GraphNode> children) {
        return new GraphNode(this.cls, this.property, children, this.isAnchor);
    }

    public static GraphNode of(Resource cls) {
        return new GraphNode(cls, null, new ArrayList<>(), false);
    }
}
