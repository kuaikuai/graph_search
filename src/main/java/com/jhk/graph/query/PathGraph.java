package com.jhk.graph.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.jena.rdf.model.Resource;

/**
 * 路径子图
 * 包含主路径节点列表和侧分支节点映射
 */
public class PathGraph {
    /** 主路径节点列表（从锚点到目标的节点序列） */
    public final List<GraphNode> mainPath = new ArrayList<>();

    /** 侧分支节点映射: parentClass → [分支节点] */
    public final Map<Resource, List<GraphNode>> sideBranches = new HashMap<>();

    public void addMainNode(GraphNode node) {
        mainPath.add(node);
    }

    public void addSideBranch(Resource parent, GraphNode branch) {
        sideBranches.computeIfAbsent(parent, k -> new ArrayList<>()).add(branch);
    }

    public boolean isEmpty() {
        return mainPath.isEmpty() && sideBranches.isEmpty();
    }
}
