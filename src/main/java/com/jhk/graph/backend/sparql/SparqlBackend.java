package com.jhk.graph.backend.sparql;

import com.jhk.graph.backend.GraphBackend;
import com.jhk.graph.config.BackendConfig.SparqlProperties;
import com.jhk.graph.dto.request.GraphQueryRequest;
import com.jhk.graph.dto.request.PathElement;
import com.jhk.graph.dto.request.PatternEdge;
import com.jhk.graph.dto.request.PatternVertex;
import com.jhk.graph.dto.response.ApiResponse;
import com.jhk.graph.dto.response.GraphQueryResponse;
import com.jhk.graph.exception.GraphQueryException;
import com.jhk.graph.query.FilterExpressionBuilder;
import com.jhk.graph.query.OntologyQueryEngine;
import com.jhk.graph.query.WhereClauseValidator;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;

import java.io.IOException;
import java.util.*;

/**
 * SPARQL 后端实现
 * 将统一 JSON 请求转换为 SPARQL 查询并执行
 */
public class SparqlBackend implements GraphBackend {

    private final OntologyQueryEngine engine;
    private final SparqlProperties config;
    private final SparqlBuilder builder;

    public SparqlBackend(SparqlProperties config) {
        this.config = config;
        this.builder = new SparqlBuilder(config);
        try {
            this.engine = new OntologyQueryEngine(config.getOntologyPath(), config.getEndpoint());
        } catch (IOException e) {
            throw new GraphQueryException("BACKEND_ERROR", "Failed to load ontology: " + e.getMessage(), e);
        } catch (Throwable t) {
            throw new GraphQueryException("BACKEND_ERROR", "Failed to initialize Jena: " + t.toString(), t);
        }
    }

    @Override
    public GraphQueryResponse execute(GraphQueryRequest request) {
        String queryType = request.getQueryType();
        if ("path".equals(queryType)) {
            return executePathQuery(request);
        } else if ("traverse".equals(queryType)) {
            return executeTraverseQuery(request);
        } else if ("pattern".equals(queryType)) {
            return executePatternQuery(request);
        } else {
            throw new GraphQueryException("INVALID_QUERY_TYPE", "Unsupported queryType: " + queryType);
        }
    }

    @Override
    public String getType() {
        return "sparql";
    }

    @Override
    public String buildSparql(GraphQueryRequest request) {
        String queryType = request.getQueryType();
        if ("path".equals(queryType)) {
            return buildPathSparql(request);
        } else if ("traverse".equals(queryType)) {
            return buildTraverseSparql(request);
        } else if ("pattern".equals(queryType)) {
            return buildPatternSparql(request);
        } else {
            throw new GraphQueryException("INVALID_QUERY_TYPE", "Unsupported queryType: " + queryType);
        }
    }

    /**
     * 生成 path 查询的 SPARQL（不执行）
     * source/target 可以是:
     *   - 字符串类型名: "Organization"
     *   - SourceTarget 对象: {"type": "Organization", "filters": {"name": "研发部"}}
     * 当有 filters 时，条件直接内联到 SPARQL WHERE 子句中
     */
    private String buildPathSparql(GraphQueryRequest request) {
        GraphQueryRequest.SourceTarget sourceObj = request.getSourceObj();
        GraphQueryRequest.SourceTarget targetObj = request.getTargetObj();

        if (sourceObj == null || sourceObj.getType() == null || sourceObj.getType().isBlank()) {
            throw new GraphQueryException("INVALID_FILTER", "source is required for path query");
        }

        String targetType = targetObj != null && targetObj.getType() != null && !targetObj.getType().isBlank()
            ? targetObj.getType() : "PainPoint";

        // 如果 source 有 filters，直接生成带 inline filter 的 SPARQL
        if (sourceObj.hasFilters()) {
            return buildPathSparqlWithInlineFilter(sourceObj.getType(), sourceObj.getFilters(), targetType, request.getTargetProperties());
        }

        // 否则使用 engine.buildQuery（source 作为锚点约束）1
        Map<String, List<String>> constraints = new HashMap<>();
        String sourceId = sourceObj.hasId() ? sourceObj.getId() : sourceObj.getType();
        constraints.put("_source_", List.of(sourceId));
        List<String> targetProps = request.getTargetProperties() != null ? List.of(request.getTargetProperties()) : null;
        return engine.buildQuery(constraints, targetType, targetProps);
    }

    /**
     * 生成带 inline filter 条件的 path SPARQL（用于有 filters 的 source）
     * 委托给 engine.buildPathQueryWithInlineSourceFilter
     */
    private String buildPathSparqlWithInlineFilter(String sourceType, Map<String, Object> sourceFilters, String targetType, String[] targetProperties) {
        List<String> props = targetProperties != null ? List.of(targetProperties) : null;
        return engine.buildPathQueryWithInlineSourceFilter(sourceType, sourceFilters, targetType, props);
    }

    /**
     * 构建从特定 source 实例出发到 target 类型的路径三元组
     */
    private String buildPathSectionFromSource(String sourceVar, String targetType,
            com.jhk.graph.query.PathGraph graph,
            Map<Resource, com.jhk.graph.query.ParentNode> parentMap,
            Set<Resource> anchorClasses) {

        List<com.jhk.graph.query.GraphNode> nodes = graph.mainPath;
        StringBuilder sb = new StringBuilder();

        // 第一跳：sourceVar --prop1--> nextClass 的实例
        if (nodes.isEmpty()) return sb.toString();

        com.jhk.graph.query.GraphNode firstNode = nodes.get(0);
        String firstVar = varNameForType(getSimpleName(firstNode.cls()));
        Resource firstProp = firstNode.property();
        if (firstProp == null && parentMap.containsKey(firstNode.cls())) {
            firstProp = parentMap.get(firstNode.cls()).property();
        }
        String firstPropName = getSimpleName(firstProp);

        sb.append(builder.edgeDecl(sourceVar, firstPropName, firstVar));

        return sb.toString();
    }

    /**
     * 直接生成带内联 filter 的完整 path SPARQL（用于 executePathQuery 和 buildPathSparql）
     * 委托给 engine.buildPathQueryWithInlineSourceFilter
     */
    String buildInlineFilterPathSparql(String sourceType, Map<String, Object> sourceFilters, String targetType, String[] targetProperties) {
        List<String> props = targetProperties != null ? List.of(targetProperties) : null;
        return engine.buildPathQueryWithInlineSourceFilter(sourceType, sourceFilters, targetType, props);
    }

    /**
     * 根据类名获取 SPARQL 变量名
     */
    private String varNameForType(String className) {
        Map<String, String> ALIAS = Map.of(
            "MetricDimensionUnit", "mdu",
            "AbnormalRule", "rule",
            "PainPoint", "target",
            "Organization", "org",
            "AnalysisScenario", "scenario",
            "Dimension", "dim"
        );
        if (ALIAS.containsKey(className)) return "?" + ALIAS.get(className);
        return "?" + Character.toLowerCase(className.charAt(0)) + className.substring(1);
    }

    /**
     * 从 PathGraph / parentMap 构建路径三元组 SPARQL 片段
     */
    private String buildPathSection(com.jhk.graph.query.PathGraph graph, Map<Resource, com.jhk.graph.query.ParentNode> parentMap, Set<Resource> anchorClasses) {
        StringBuilder sb = new StringBuilder();
        List<com.jhk.graph.query.GraphNode> nodes = graph.mainPath;

        for (int i = 0; i < nodes.size() - 1; i++) {
            com.jhk.graph.query.GraphNode current = nodes.get(i);
            com.jhk.graph.query.GraphNode next = nodes.get(i + 1);

            String fromVar = varNameForType(getSimpleName(current.cls()));
            String toVar = varNameForType(getSimpleName(next.cls()));
            Resource propRes;
            com.jhk.graph.query.ParentNode pnNext = parentMap.get(next.cls());
            if (pnNext != null) {
                propRes = pnNext.property();
            } else {
                propRes = current.property() != null ? current.property() : next.property();
            }
            String propPrefix = getPropertyPrefix(propRes);

            sb.append("  ").append(fromVar).append(" ");
            sb.append(propPrefix).append(":").append(getSimpleName(propRes));
            sb.append(" ").append(toVar).append(" .\n");
        }

        // 侧分支
        for (Map.Entry<Resource, List<com.jhk.graph.query.GraphNode>> entry : graph.sideBranches.entrySet()) {
            Resource parent = entry.getKey();
            String parentVar = varNameForType(getSimpleName(parent));
            for (com.jhk.graph.query.GraphNode branch : entry.getValue()) {
                String branchVar = varNameForType(getSimpleName(branch.cls()));
                String propPrefix = getPropertyPrefix(branch.property());
                sb.append("  ").append(parentVar).append(" ");
                sb.append(propPrefix).append(":").append(getSimpleName(branch.property()));
                sb.append(" ").append(branchVar).append(" .\n");
            }
        }

        return sb.toString();
    }

    private String getSimpleName(Resource res) {
        if (res == null) return null;
        String uri = res.getURI();
        if (uri == null) return null;
        int lastSlash = uri.lastIndexOf('/');
        return lastSlash >= 0 ? uri.substring(lastSlash + 1) : uri;
    }

    private String getPropertyPrefix(Resource prop) {
        if (prop == null) return "baprop";
        String uri = prop.getURI();
        if (uri != null && uri.contains("/property/")) return "baprop";
        return "baprop";
    }

    /**
     * 生成 traverse 查询的 SPARQL（展示第一跳展开，不执行完整 BFS）
     */
    private String buildTraverseSparql(GraphQueryRequest request) {
        if (request.getSource() == null || request.getSource().isBlank()) {
            throw new GraphQueryException("INVALID_FILTER", "source required for traverse query");
        }
        String startNode = request.getSource();
        String direction = request.getDirection() != null ? request.getDirection() : "out";
        String[] edgeLabels = request.getEdgeLabels();

        String propFilter = buildEdgeLabelsFilter(edgeLabels);

        String sparql;
        String prefixBlock = builder.buildPrefixBlock();
        if ("out".equals(direction) || "both".equals(direction)) {
            sparql = prefixBlock + "SELECT DISTINCT ?neighbor ?prop WHERE {\n"
                + "    ?neighbor ?prop <" + startNode + "> .\n"
                + "    ?prop rdf:type " + builder.getPropPrefix() + ":Property .\n"
                + propFilter + "\n"
                + "}";
        } else {
            sparql = prefixBlock + "SELECT DISTINCT ?neighbor ?prop WHERE {\n"
                + "    <" + startNode + "> ?prop ?neighbor .\n"
                + "    ?prop rdf:type " + builder.getPropPrefix() + ":Property .\n"
                + propFilter + "\n"
                + "}";
        }
        return sparql;
    }

    private String buildEdgeLabelsFilter(String[] edgeLabels) {
        if (edgeLabels == null || edgeLabels.length == 0) {
            return "";
        }
        StringBuilder filter = new StringBuilder("FILTER(?prop IN (");
        for (int i = 0; i < edgeLabels.length; i++) {
            filter.append("<http://www.jhk.com/finance/business-analysis/property/").append(edgeLabels[i]).append(">");
            if (i < edgeLabels.length - 1) filter.append(", ");
        }
        filter.append("))");
        return filter.toString();
    }

/**
     * 生成 pattern 查询的 SPARQL（复用 executePatternQuery 的构建逻辑）
     */
    private String buildPatternSparql(GraphQueryRequest request) {
        PatternVertex[] nodes = request.getNodes();
        PatternEdge[] edges = request.getEdges();
        PathElement[] path = request.getPath();

        // Support both new nodes/edges and legacy path format
        if ((nodes == null || edges == null) && (path == null || path.length == 0)) {
            throw new GraphQueryException("INVALID_PATTERN", "path or nodes/edges required for pattern query");
        }

        String[] selectVars = request.getSelect();
        String whereClause = request.getWhere();

        StringBuilder sparql = new StringBuilder();
        sparql.append(builder.buildPrefixBlock());

        // SELECT
        sparql.append("SELECT DISTINCT ");
        if (nodes != null && edges != null) {
            StringBuilder selectParts = new StringBuilder();
            for (int i = 0; i < nodes.length; i++) {
                selectParts.append("?").append(nodes[i].getAs());
                if (i < nodes.length - 1) selectParts.append(" ");
            }
            sparql.append(selectParts);
        } else if (selectVars != null && selectVars.length > 0) {
            for (int i = 0; i < selectVars.length; i++) {
                sparql.append("?").append(selectVars[i]);
                if (i < selectVars.length - 1) sparql.append(" ");
            }
        } else {
            sparql.append("*");
        }
        sparql.append(" WHERE {\n");

        // Build pattern triples
        if (nodes != null && edges != null) {
            buildSparqlFromNodesEdges(sparql, nodes, edges);
        } else {
            // Legacy path structure
            String prevVar = null;
            for (int i = 0; i < path.length; i++) {
                PathElement elem = path[i];
                if (!elem.isEdge()) {
                    // Node
                    String var;
                    if (elem.getAs() != null && !elem.getAs().isBlank()) {
                        var = "?" + elem.getAs();
                    } else {
                        var = "?v" + i;
                    }
                    if (elem.getType() != null) {
                        sparql.append(builder.typeDecl(var, elem.getType()));
                    }
                    prevVar = var;
                } else {
                    // Edge
                    if (prevVar == null) {
                        throw new GraphQueryException("INVALID_PATTERN", "edge at index " + i + " has no preceding node");
                    }
                    String from = prevVar;
                    PathElement nextNode = path[i + 1];
                    String to;
                    if (nextNode.getAs() != null && !nextNode.getAs().isBlank()) {
                        to = "?" + nextNode.getAs();
                    } else {
                        to = "?v" + (i + 1);
                    }
                    String edgeName = elem.getEdge() != null ? elem.getEdge() : "";
                    sparql.append(builder.edgeDecl(from, edgeName, to));
                    prevVar = to;
                }
            }
        }

        // Additional where clause
        if (whereClause != null && !whereClause.isBlank()) {
            sparql.append("  ").append(whereClause).append("\n");
        }

        sparql.append("}");
        return sparql.toString();
}

    /**
     * path 查询执行
     * source = 锚点约束 (组织等)
     * target = 目标类 (PainPoint)
     * 支持 SourceTarget 对象: {"type": "Organization", "filters": {"name": "研发部"}}
     * filters 直接内联到 SPARQL WHERE 子句中，不做预解析
     */
    private GraphQueryResponse executePathQuery(GraphQueryRequest request) {
        // 1. 解析 source
        GraphQueryRequest.SourceTarget sourceObj = request.getSourceObj();
        if (sourceObj == null || sourceObj.getType() == null || sourceObj.getType().isBlank()) {
            throw new GraphQueryException("INVALID_FILTER", "source is required for path query");
        }

        // 2. 解析 target
        GraphQueryRequest.SourceTarget targetObj = request.getTargetObj();
        String targetType = targetObj != null && targetObj.getType() != null && !targetObj.getType().isBlank()
            ? targetObj.getType() : "PainPoint";

        // 获取 target 属性列表
        String[] targetPropsArray = request.getTargetProperties();
        List<String> targetProps = targetPropsArray != null ? List.of(targetPropsArray) : null;

        List<Map<String, String>> results;
        if (sourceObj.hasFilters()) {
            // 有 filters: 生成带内联 filter 条件的 SPARQL，直接执行
            String sparql = buildInlineFilterPathSparql(sourceObj.getType(), sourceObj.getFilters(), targetType, targetPropsArray);
            results = engine.executeRawQuery(sparql);
        } else {
            // 无 filters: 使用标准的 engine.query（锚点约束方式）
            Map<String, List<String>> constraints = new HashMap<>();
            String sourceId = sourceObj.hasId() ? sourceObj.getId() : sourceObj.getType();
            constraints.put("_source_", List.of(sourceId));
            results = engine.query(constraints, targetType, targetProps);
        }

        // 3. 检查结果：如果指定了 filters 但没有找到匹配节点，抛出 VERTEX_NOT_FOUND
        if (results.isEmpty() && sourceObj.hasFilters()) {
            throw new GraphQueryException("VERTEX_NOT_FOUND",
                "Source node not found with filters: " + sourceObj.getFilters(),
                Map.of("type", sourceObj.getType(), "filters", sourceObj.getFilters()));
        }

        // 4. 转换响应
        GraphQueryResponse response = new GraphQueryResponse();
        response.setQueryType("path");

        List<GraphQueryResponse.PathResult> paths = new ArrayList<>();
        for (Map<String, String> row : results) {
            GraphQueryResponse.PathResult path = new GraphQueryResponse.PathResult();

            // 构建 target 节点属性（包含指定的 targetProperties）
            Map<String, Object> targetPropsMap = new HashMap<>();
            if (targetProps != null) {
                for (String prop : targetProps) {
                    String value = row.get(prop);
                    if (value != null) {
                        targetPropsMap.put(prop, value);
                    }
                }
            }

            // 构建路径节点
            List<GraphQueryResponse.NodeResult> nodes = new ArrayList<>();
            // target (PainPoint) - 从结果中取，包含属性
            String targetId = row.get("target");
            if (targetId != null) {
                nodes.add(new GraphQueryResponse.NodeResult(targetId, "PainPoint", targetPropsMap));
            }
            // rule
            String ruleId = row.get("rule");
            if (ruleId != null) {
                nodes.add(new GraphQueryResponse.NodeResult(ruleId, "AbnormalRule", Map.of()));
            }
            // mdu
            String mduId = row.get("mdu");
            if (mduId != null) {
                nodes.add(new GraphQueryResponse.NodeResult(mduId, "MetricDimensionUnit", Map.of()));
            }
            // scenario
            String scenarioId = row.get("scenario");
            if (scenarioId != null) {
                nodes.add(new GraphQueryResponse.NodeResult(scenarioId, "AnalysisScenario", Map.of()));
            }
            // org
            String orgId = row.get("org");
            if (orgId != null) {
                nodes.add(new GraphQueryResponse.NodeResult(orgId, "Organization", Map.of()));
            }

            path.setNodes(nodes);
            path.setTotalHops(nodes.size() - 1);

            // 构建路径边
            List<GraphQueryResponse.EdgeResult> edges = new ArrayList<>();
            if (orgId != null && scenarioId != null) {
                edges.add(new GraphQueryResponse.EdgeResult(null, scenarioId, orgId, "involvesScenario", null));
            }
            if (scenarioId != null && mduId != null) {
                edges.add(new GraphQueryResponse.EdgeResult(null, scenarioId, mduId, "involvesMetricDimensionUnit", null));
            }
            if (mduId != null && ruleId != null) {
                edges.add(new GraphQueryResponse.EdgeResult(null, mduId, ruleId, "hasAbnormalRule", null));
            }
            if (ruleId != null && targetId != null) {
                edges.add(new GraphQueryResponse.EdgeResult(null, ruleId, targetId, "correspondsToPainPoint", null));
            }

            path.setEdges(edges);
            path.setDescription(buildDescription(nodes, edges));
            paths.add(path);
        }

        response.setPaths(paths);
        response.setTotalPaths(paths.size());

        return response;
    }

    // ==================== traverse query ====================

    /**
     * traverse 查询：按方向和深度遍历图，返回可达节点
     * source = 起点 + 过滤条件
     * direction = out | in | both
     * maxHops = 最大深度
     * edgeLabels = 只遍历指定边（可选）
     * resultScope = nodes | paths
     */
    private GraphQueryResponse executeTraverseQuery(GraphQueryRequest request) {
        if (request.getSource() == null || request.getSource().isBlank()) {
            throw new GraphQueryException("INVALID_FILTER", "source required for traverse query");
        }

        String startNode = request.getSource();
        String direction = request.getDirection() != null ? request.getDirection() : "out";
        Integer maxHops = request.getMaxHops() != null ? request.getMaxHops() : 3;
        String[] edgeLabels = request.getEdgeLabels();
        String resultScope = request.getResultScope() != null ? request.getResultScope() : "nodes";

        String startInstanceUri = startNode;

        // BFS/DFS 遍历
        Set<String> visited = new HashSet<>();
        List<String> currentLevel = new ArrayList<>();
        currentLevel.add(startInstanceUri);
        visited.add(startInstanceUri);

        // 按跳数收集节点: hop -> list of node URIs
        Map<Integer, Set<String>> byHop = new HashMap<>();
        byHop.put(0, Set.of(startInstanceUri));

        for (int hop = 1; hop <= maxHops; hop++) {
            List<String> nextLevel = new ArrayList<>();
            for (String instanceUri : currentLevel) {
                List<String> neighbors = getNeighbors(instanceUri, direction, edgeLabels);
                for (String neighbor : neighbors) {
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        nextLevel.add(neighbor);
                        byHop.computeIfAbsent(hop, k -> new HashSet<>()).add(neighbor);
                    }
                }
            }
            if (nextLevel.isEmpty()) break;
            currentLevel = nextLevel;
        }

        // 构建响应
        GraphQueryResponse response = new GraphQueryResponse();
        response.setQueryType("traverse");

        if ("paths".equals(resultScope)) {
            // 返回所有路径（从起点到每个可达节点）
            List<GraphQueryResponse.PathResult> paths = new ArrayList<>();
            for (String visitedUri : visited) {
                if (visitedUri.equals(startInstanceUri)) continue;
                int hop = findHop(byHop, visitedUri);
                GraphQueryResponse.PathResult path = new GraphQueryResponse.PathResult();
                path.setTotalHops(hop);

                List<GraphQueryResponse.NodeResult> nodes = new ArrayList<>();
                nodes.add(new GraphQueryResponse.NodeResult(startInstanceUri, inferType(startInstanceUri), Map.of()));
                nodes.add(new GraphQueryResponse.NodeResult(visitedUri, inferType(visitedUri), Map.of()));
                path.setNodes(nodes);

                List<GraphQueryResponse.EdgeResult> edges = new ArrayList<>();
                edges.add(new GraphQueryResponse.EdgeResult(null, startInstanceUri, visitedUri, null, null));
                path.setEdges(edges);
                paths.add(path);
            }
            response.setPaths(paths);
            response.setTotalPaths(paths.size());
        } else {
            // 返回节点列表（按跳数分组）
            Map<String, List<String>> byHopStr = new HashMap<>();
            for (Map.Entry<Integer, Set<String>> e : byHop.entrySet()) {
                byHopStr.put(String.valueOf(e.getKey()), new ArrayList<>(e.getValue()));
            }
            response.setByHop(byHopStr);

            List<GraphQueryResponse.NodeResult> nodes = new ArrayList<>();
            for (String uri : visited) {
                nodes.add(new GraphQueryResponse.NodeResult(uri, inferType(uri), Map.of()));
            }
            response.setNodes(nodes);
        }

        return response;
    }

    private List<String> getNeighbors(String instanceUri, String direction, String[] edgeLabels) {
        List<String> neighbors = new ArrayList<>();

        String propFilter = "";
        if (edgeLabels != null && edgeLabels.length > 0) {
            StringBuilder filter = new StringBuilder("FILTER(?prop IN (");
            for (int i = 0; i < edgeLabels.length; i++) {
                filter.append("<http://www.jhk.com/finance/business-analysis/property/").append(edgeLabels[i]).append(">");
                if (i < edgeLabels.length - 1) filter.append(", ");
            }
            filter.append("))");
            propFilter = filter.toString();
        }

        // OUT: subject --pred--> instanceUri (instanceUri 是 object)
        if ("out".equals(direction) || "both".equals(direction)) {
            String sparqlOut = builder.buildPrefixBlock()
                + "SELECT DISTINCT ?neighbor WHERE {\n"
                + "    ?neighbor ?prop <" + instanceUri + "> .\n"
                + "    ?prop rdf:type " + builder.getPropPrefix() + ":Property .\n"
                + (propFilter.isEmpty() ? "" : "    " + propFilter + "\n")
                + "}";

            try {
                Query query = QueryFactory.create(sparqlOut);
                try (QueryExecution qe = QueryExecutionFactory.create(query, engine.getModel())) {
                    ResultSet rs = qe.execSelect();
                    while (rs.hasNext()) {
                        QuerySolution sol = rs.next();
                        Resource neighbor = sol.getResource("neighbor");
                        if (neighbor != null) neighbors.add(neighbor.getURI());
                    }
                }
            } catch (Exception e) {
                // ignore - return empty neighbors
            }
        }

        // IN: instanceUri --pred--> object (instanceUri 是 subject)
        if ("in".equals(direction) || "both".equals(direction)) {
            String sparqlIn = builder.buildPrefixBlock()
                + "SELECT DISTINCT ?neighbor WHERE {\n"
                + "    <" + instanceUri + "> ?prop ?neighbor .\n"
                + "    ?prop rdf:type " + builder.getPropPrefix() + ":Property .\n"
                + (propFilter.isEmpty() ? "" : "    " + propFilter + "\n")
                + "}";

            try {
                Query query = QueryFactory.create(sparqlIn);
                try (QueryExecution qe = QueryExecutionFactory.create(query, engine.getModel())) {
                    ResultSet rs = qe.execSelect();
                    while (rs.hasNext()) {
                        QuerySolution sol = rs.next();
                        RDFNode neighbor = sol.get("neighbor");
                        if (neighbor != null && neighbor.isResource()) {
                            neighbors.add(neighbor.asResource().getURI());
                        }
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }

        return neighbors;
    }

    private int findHop(Map<Integer, Set<String>> byHop, String uri) {
        for (Map.Entry<Integer, Set<String>> e : byHop.entrySet()) {
            if (e.getValue().contains(uri)) return e.getKey();
        }
        return -1;
    }

    private String inferType(String instanceUri) {
        // 从 RDF 类型推断
        String typeSparql = builder.buildPrefixBlock()
            + "SELECT ?type WHERE {\n"
            + "    <" + instanceUri + "> rdf:type ?type .\n"
            + "} LIMIT 1";

        try {
            Query query = QueryFactory.create(typeSparql);
            try (QueryExecution qe = QueryExecutionFactory.create(query, engine.getModel())) {
                ResultSet rs = qe.execSelect();
                if (rs.hasNext()) {
                    Resource type = rs.next().getResource("type");
                    if (type != null) {
                        String uri = type.getURI();
                        int idx = uri.lastIndexOf('/');
                        return idx >= 0 ? uri.substring(idx + 1) : uri;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "Unknown";
    }

    // ==================== pattern query ====================

    /**
     * pattern 查询：基于路径模式匹配查询
     * path = 节点和边的交替序列 [node, edge, node, edge, node, ...]
     * select = 要返回的变量列表
     * where = 额外的 SPARQL FILTER 子句
     */
    private GraphQueryResponse executePatternQuery(GraphQueryRequest request) {
        // Support new nodes+edges structure or legacy path array
        com.jhk.graph.dto.request.PatternVertex[] nodes = request.getNodes();
        com.jhk.graph.dto.request.PatternEdge[] edges = request.getEdges();
        PathElement[] path = request.getPath();

        if ((nodes == null || edges == null) && (path == null || path.length == 0)) {
            throw new GraphQueryException("INVALID_PATTERN", "path or nodes/edges required for pattern query");
        }

        String[] selectVars = request.getSelect();
        String whereClause = request.getWhere();

        // 构建 SPARQL
        StringBuilder sparql = new StringBuilder();

        // PREFIX
        sparql.append(builder.buildPrefixBlock());

        // SELECT - return all selected variables
        sparql.append("SELECT DISTINCT ");
        if (nodes != null && edges != null) {
            // New structure: select all node ids
            StringBuilder selectParts = new StringBuilder();
            for (int i = 0; i < nodes.length; i++) {
                selectParts.append("?").append(nodes[i].getAs());
                if (i < nodes.length - 1) selectParts.append(" ");
            }
            sparql.append(selectParts);
        } else if (selectVars != null && selectVars.length > 0) {
            for (int i = 0; i < selectVars.length; i++) {
                sparql.append("?").append(selectVars[i]);
                if (i < selectVars.length - 1) sparql.append(" ");
            }
        } else {
            sparql.append("*");
        }
        sparql.append(" WHERE {\n");

        // Build pattern triples
        if (nodes != null && edges != null) {
            // New nodes + edges structure
            buildSparqlFromNodesEdges(sparql, nodes, edges);
        } else {
            // Legacy path structure
            buildSparqlFromPath(sparql, path, selectVars, whereClause);
        }

        // 额外的 where 子句
        if (whereClause != null && !whereClause.isBlank()) {
            sparql.append("  ").append(whereClause).append("\n");
        }

        sparql.append("}\n");

        // 执行查询
        List<GraphQueryResponse.PathResult> pathResults = new ArrayList<>();
        List<GraphQueryResponse.NodeResult> allNodes = new ArrayList<>();
        try {
            Query query = QueryFactory.create(sparql.toString());
            try (QueryExecution qe = QueryExecutionFactory.create(query, engine.getModel())) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    GraphQueryResponse.PathResult pathResult = new GraphQueryResponse.PathResult();
                    List<GraphQueryResponse.NodeResult> pathNodes = new ArrayList<>();
                    List<GraphQueryResponse.EdgeResult> pathEdges = new ArrayList<>();

                    // Get column names
                    var columns = rs.getResultVars();
                    for (String varName : columns) {
                        RDFNode node = sol.get(varName);
                        if (node != null && node.isResource()) {
                            Resource res = node.asResource();
                            String type = inferType(res.getURI());
                            pathNodes.add(new GraphQueryResponse.NodeResult(res.getURI(), type, Map.of()));
                            allNodes.add(new GraphQueryResponse.NodeResult(res.getURI(), type, Map.of()));
                        }
                    }

                    pathResult.setNodes(pathNodes);
                    pathResult.setEdges(pathEdges);
                    pathResult.setTotalHops(pathEdges.size());
                    pathResults.add(pathResult);
                }
            }
        } catch (Exception e) {
            throw new GraphQueryException("BACKEND_ERROR", "pattern query failed: " + e.getMessage(), e);
        }

        GraphQueryResponse response = new GraphQueryResponse();
        response.setQueryType("pattern");
        response.setNodes(allNodes);
        response.setPaths(pathResults);
        response.setTotalPaths(pathResults.size());
        return response;
    }

    private void buildSparqlFromNodesEdges(StringBuilder sparql, com.jhk.graph.dto.request.PatternVertex[] nodes, com.jhk.graph.dto.request.PatternEdge[] edges) {
        // Build variable declarations and type constraints for nodes
        for (com.jhk.graph.dto.request.PatternVertex v : nodes) {
            String var = "?" + v.getAs();
            if (v.getType() != null) {
                sparql.append(builder.typeDecl(var, v.getType()));
            }
            if (v.hasFilters()) {
                FilterExpressionBuilder.FilterResult filterResult =
                    FilterExpressionBuilder.parseFilters(v.getFilters(), var);
                sparql.append(filterResult.getTriples());
                sparql.append(filterResult.getFilters());
            }
        }

        // Build edge triples
        for (com.jhk.graph.dto.request.PatternEdge e : edges) {
            String from = "?" + e.getFrom();
            String to = "?" + e.getTo();
            String label = e.getLabel() != null ? e.getLabel() : "*";
            sparql.append(builder.edgeDecl(from, label, to));

            // Edge property filters
            if (e.hasFilters()) {
                String edgeVar = "?_" + e.getFrom() + "_" + e.getTo();
                sparql.append(builder.edgeDecl(from, label, to));
                // Note: SPARQL doesn't have edge property filters like nGQL, so we skip edge props for now
            }
        }
    }

    private void buildSparqlFromPath(StringBuilder sparql, PathElement[] path, String[] selectVars, String whereClause) {
        // Validate where clause for legacy path structure
        WhereClauseValidator.ValidationResult validation = WhereClauseValidator.validate(whereClause, selectVars, path);
        if (!validation.isValid()) {
            throw new GraphQueryException("INVALID_PATTERN", "Invalid where clause: " + validation.getErrorMessage());
        }

        String prevVar = null;
        for (int i = 0; i < path.length; i++) {
            PathElement elem = path[i];

            if (!elem.isEdge()) {
                String var;
                if (elem.getAs() != null && !elem.getAs().isBlank()) {
                    var = "?" + elem.getAs();
                } else {
                    var = "?v" + i;
                    elem.setAs(var.substring(1));
                }

                if (elem.getType() != null) {
                    sparql.append(builder.typeDecl(var, elem.getType()));
                }

                if (elem.getFilters() != null && !elem.getFilters().isEmpty()) {
                    FilterExpressionBuilder.FilterResult filterResult =
                        FilterExpressionBuilder.parseFilters(elem.getFilters(), var);
                    sparql.append(filterResult.getTriples());
                    sparql.append(filterResult.getFilters());
                }

                prevVar = var;
            } else {
                String from = prevVar;
                PathElement nextNode = path[i + 1];
                String to;
                if (nextNode.getAs() != null && !nextNode.getAs().isBlank()) {
                    to = "?" + nextNode.getAs();
                } else {
                    to = "?v" + (i + 1);
                    nextNode.setAs("v" + (i + 1));
                }

                String edgeName = elem.getEdge();
                sparql.append(builder.edgeDecl(from, edgeName, to));
                prevVar = to;
            }
        }
    }

    private String resolveInstanceUri(String type, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            throw new GraphQueryException("INVALID_FILTER", "source filters required for traverse query");
        }
        // 取第一个 filter value 作为实例 URI
        for (Object val : filters.values()) {
            return val.toString();
        }
        throw new GraphQueryException("INVALID_FILTER", "source filters required");
    }

    /**
     * 构建路径字符串描述
     * 格式: org(冰冷事业部) -> involvesScenario -> scenario(营业收入) -> ...
     */
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
        for (var entry : node.entrySet()) {
            String key = entry.getKey();
            if (!"id".equals(key) && !"type".equals(key) && entry.getValue() != null) {
                return entry.getValue().toString();
            }
        }
        Object id = node.get("id");
        return id != null ? id.toString() : "?";
    }
}
