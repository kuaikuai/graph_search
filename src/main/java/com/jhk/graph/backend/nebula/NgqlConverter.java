package com.jhk.graph.backend.nebula;

import com.jhk.graph.dto.request.GraphQueryRequest;
import com.jhk.graph.dto.request.PathElement;
import com.jhk.graph.dto.request.PatternEdge;
import com.jhk.graph.dto.request.PatternVertex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class NgqlConverter {

    public String toNgql(GraphQueryRequest request) {
        return switch (request.getQueryType()) {
            case "path" -> convertPath(request);
            case "traverse" -> convertTraverse(request);
            case "pattern" -> convertPattern(request);
            default -> throw new IllegalArgumentException("Unsupported queryType: " + request.getQueryType());
        };
    }

    private String convertPath(GraphQueryRequest request) {
        // Path query: MATCH with separate src/dst/edges for proper parsing
        GraphQueryRequest.SourceTarget source = request.getSourceObj();
        GraphQueryRequest.SourceTarget target = request.getTargetObj();

        // If direction is "in" and source is not provided but target is, swap them
        String direction = request.getDirection();
        if ("in".equals(direction) && source == null && target != null) {
            source = target;
            target = null;
        }

        if (source == null) {
            throw new IllegalArgumentException("source is required for path query");
        }

        String sourceType = source.getType();
        int minHops = request.getMinHops() != null ? request.getMinHops() : 1;
        int maxHops = request.getMaxHops() != null ? request.getMaxHops() : 5;

        StringBuilder ngql = new StringBuilder();

        // For "in" direction, use REVERSELY to traverse incoming edges
        if ("in".equals(direction)) {
            ngql.append("MATCH p = (src:").append(sourceType).append(")<-[*").append(minHops).append("..").append(maxHops).append("]-(dst");
        } else {
            ngql.append("MATCH p = (src:").append(sourceType).append(")-[*").append(minHops).append("..").append(maxHops).append("]->(dst");
        }
        if (target != null) {
            ngql.append(":").append(target.getType());
        }
        ngql.append(") ");

// Build WHERE clause with source filters (use tag.property format)
        List<String> conditions = new ArrayList<>();

        if (source.hasFilters()) {
            // For "in" direction, the original target becomes src, so filter uses dst.xxx
            String filterVar = "in".equals(direction) ? "dst" : "src";
            String prefix = filterVar + "." + sourceType;
            String filterExpr = parseConditionToNgql(prefix, source.getFilters());
            if (!filterExpr.isEmpty()) {
                conditions.add(filterExpr);
            }
        }

        if (target != null && target.hasFilters()) {
            // For "in" direction, the original source becomes dst, so filter uses src.xxx
            String filterVar = "in".equals(direction) ? "src" : "dst";
            String prefix = filterVar + "." + target.getType();
            String filterExpr = parseConditionToNgql(prefix, target.getFilters());
            if (!filterExpr.isEmpty()) {
                conditions.add(filterExpr);
            }
        }

        if (!conditions.isEmpty()) {
            ngql.append("WHERE ").append(String.join(" AND ", conditions)).append(" ");
        }

        // Return path variable for proper parsing with asPath()
        ngql.append("RETURN p");

        return ngql.toString();
    }

    private String convertTraverse(GraphQueryRequest request) {
        // Traverse query: MATCH pattern with variable hops, returns full paths
        GraphQueryRequest.SourceTarget source = request.getSourceObj();
        int minHops = request.getMinHops() != null ? request.getMinHops() : 1;
        int maxHops = request.getMaxHops() != null ? request.getMaxHops() : 3;
        int limit = request.getLimit() != null ? request.getLimit() : 10;
        String direction = request.getDirection() != null ? request.getDirection() : "out";

        StringBuilder ngql = new StringBuilder();
        String sourceType = source.getType();

        // Use MATCH with variable hop range, return path
        if ("in".equals(direction)) {
            ngql.append("MATCH p = (src:").append(sourceType).append(")<-[*").append(minHops).append("..").append(maxHops).append("]-(dst) ");
        } else if ("both".equals(direction)) {
            ngql.append("MATCH p = (src:").append(sourceType).append(")-[*").append(minHops).append("..").append(maxHops).append("]-(dst) ");
        } else {
            ngql.append("MATCH p = (src:").append(sourceType).append(")-[*").append(minHops).append("..").append(maxHops).append("]->(dst) ");
        }

        // Source filters in WHERE clause
        List<String> conditions = new ArrayList<>();
        if (source.hasFilters()) {
            String prefix = "src." + sourceType;
            String filterExpr = parseConditionToNgql(prefix, source.getFilters());
            if (!filterExpr.isEmpty()) {
                conditions.add(filterExpr);
            }
        }

        if (!conditions.isEmpty()) {
            ngql.append("WHERE ").append(String.join(" AND ", conditions)).append(" ");
        }

        // Return path with limit
        ngql.append("RETURN p LIMIT ").append(limit);

        return ngql.toString();
    }

    private String convertPattern(GraphQueryRequest request) {
        // Pattern query: MATCH
        // Support new structure (nodes + edges) or legacy path array
        PatternVertex[] nodes = request.getNodes();
        PatternEdge[] edges = request.getEdges();
        String[] selectVars = request.getSelect();

        if (nodes == null || edges == null) {
            throw new IllegalArgumentException("nodes and edges are required for pattern query");
        }
        return convertPatternNew(request, nodes, edges, selectVars);
    }

    private String convertPatternNew(GraphQueryRequest request, PatternVertex[] nodes, PatternEdge[] edges, String[] selectVars) {
        // Build node map for lookup
        Map<String, PatternVertex> nodeMap = new java.util.HashMap<>();
        for (PatternVertex v : nodes) {
            nodeMap.put(v.getId(), v);
        }

        StringBuilder ngql = new StringBuilder();
        // Track variables already declared across segments
        Set<String> knownVars = new java.util.HashSet<>();
        List<String> allPathVars = new ArrayList<>();
        List<String> knownList = new ArrayList<>(); // ordered known vars for WITH clause
        Set<String> knownListSet = new java.util.HashSet<>(); // dedup helper for knownList
        Set<String> filterDeclared = new java.util.HashSet<>(); // track which nodes' filters were added to WHERE

        // Step 1: Group edges into chains (edges[i].from == edges[i-1].to => same chain)
        java.util.List<java.util.List<PatternEdge>> chains = new java.util.ArrayList<>();
        java.util.List<PatternEdge> currentChain = new java.util.ArrayList<>();
        currentChain.add(edges[0]);
        for (int i = 1; i < edges.length; i++) {
            if (edges[i].getFrom().equals(edges[i - 1].getTo())) {
                currentChain.add(edges[i]);
            } else {
                chains.add(currentChain);
                currentChain = new java.util.ArrayList<>();
                currentChain.add(edges[i]);
            }
        }
        chains.add(currentChain);

        int totalChains = chains.size();

        // Step 2: Generate each chain as a MATCH segment
        int edgeCounter = 0;
        for (int ci = 0; ci < totalChains; ci++) {
            if (ci > 0) ngql.append(" ");

            java.util.List<PatternEdge> chainEdges = chains.get(ci);
            int chainSize = chainEdges.size();

            // MATCH pN=(firstNode ...)
            String pathVar = "p" + (ci + 1);
            allPathVars.add(pathVar);
            ngql.append("MATCH ").append(pathVar).append("=(");
            PatternEdge firstEdge = chainEdges.get(0);
            String firstFrom = firstEdge.getFrom();
            PatternVertex firstFromV = nodeMap.get(firstFrom);

            if (!knownVars.contains(firstFrom)) {
                ngql.append(firstFrom).append(":").append(firstFromV.getType());
                knownVars.add(firstFrom);
                if (knownListSet.add(firstFrom)) knownList.add(firstFrom);
                filterDeclared.add(firstFrom);
            } else {
                ngql.append(firstFrom);
            }
            ngql.append(")");

            // Emit each edge in the chain
            for (PatternEdge edge : chainEdges) {
                edgeCounter++;
                String edgeVar = "e" + edgeCounter;
                String toId = edge.getTo();
                PatternVertex toV = nodeMap.get(toId);
                String dir = edge.getEffectiveDirection();
                String edgePart = edge.getLabel() != null ? edge.getLabel() : "*";

                // Edge pattern
                if (edge.isVariableHops()) {
                    int min = edge.getMinHops() != null ? edge.getMinHops() : 1;
                    int max = edge.getMaxHops() != null ? edge.getMaxHops() : min;
                    if ("in".equals(dir)) {
                        ngql.append("<-[e").append(edgeCounter).append(":").append(edgePart).append("*").append(min).append("..").append(max).append("]-(");
                    } else {
                        ngql.append("-[e").append(edgeCounter).append(":").append(edgePart).append("*").append(min).append("..").append(max).append("]->(");
                    }
                } else {
                    if ("in".equals(dir)) {
                        ngql.append("<-[e").append(edgeCounter).append(":").append(edgePart).append("]-(");
                    } else {
                        ngql.append("-[e").append(edgeCounter).append(":").append(edgePart).append("]->(");
                    }
                }

                // Target node
                if (!knownVars.contains(toId)) {
                    ngql.append(toId).append(":").append(toV.getType()).append(")");
                    knownVars.add(toId);
                    if (knownListSet.add(toId)) knownList.add(toId);
                    filterDeclared.add(toId);
                } else {
                    ngql.append(toId).append(")");
                }

                // Track edge var
                knownVars.add(edgeVar);
                if (knownListSet.add(edgeVar)) knownList.add(edgeVar);
            }

            // Track path var for this chain
            knownVars.add(pathVar);
            if (knownListSet.add(pathVar)) knownList.add(pathVar);

            // Build WHERE clause for this segment (collect filters for all nodes/edges in this chain)
            List<String> segConditions = new ArrayList<>();

            int firstEdgeIdx = edgeCounter - chainSize + 1;
            for (int ei = 0; ei < chainSize; ei++) {
                PatternEdge edge = chainEdges.get(ei);
                int curEdgeIdx = firstEdgeIdx + ei;

                // Source node filters
                String fromId = edge.getFrom();
                PatternVertex fromV = nodeMap.get(fromId);
                if (fromV != null && fromV.hasFilters() && filterDeclared.contains(fromId)) {
                    String filterExpr = buildFilterConditions(fromId, fromV.getFilters());
                    if (!filterExpr.isEmpty()) {
                        segConditions.add(filterExpr);
                        filterDeclared.remove(fromId);
                    }
                }

                // Target node filters
                String toId = edge.getTo();
                PatternVertex toV = nodeMap.get(toId);
                if (toV != null && toV.hasFilters() && filterDeclared.contains(toId)) {
                    String filterExpr = buildFilterConditions(toId, toV.getFilters());
                    if (!filterExpr.isEmpty()) {
                        segConditions.add(filterExpr);
                        filterDeclared.remove(toId);
                    }
                }

                // Edge filters
                if (edge.hasFilters()) {
                    String ev = "e" + curEdgeIdx;
                    for (Map.Entry<String, Object> f : edge.getFilters().entrySet()) {
                        segConditions.add(ev + "." + f.getKey() + " == '" + f.getValue() + "'");
                    }
                }
            }

            if (!segConditions.isEmpty()) {
                ngql.append(" WHERE ").append(String.join(" AND ", segConditions));
            }

            // WITH clause: pass all known vars forward (except for last chain)
            if (ci < totalChains - 1) {
                ngql.append(" WITH ").append(String.join(", ", knownList));
            }
        }

        // Step 3: Handle remaining node filters (nodes that are NOT sources/targets of any edge)
        // and were not yet added to declared (edge case)
        // ... (skipped for now, should be handled by adding them to last segment's WHERE)

        // RETURN clause
        ngql.append(" RETURN ");
        if (selectVars != null && selectVars.length > 0) {
            ngql.append(String.join(", ", selectVars));
        } else {
            ngql.append(String.join(", ", allPathVars));
        }

        return ngql.toString();
    }

    private String buildFilterConditions(String prefix, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        return parseConditionToNgql(prefix, filters);
    }

    @SuppressWarnings("unchecked")
    private String parseConditionToNgql(String prefix, Map<String, Object> condition) {
        if (condition == null || condition.isEmpty()) {
            return "";
        }

        // Check for $and
        if (condition.containsKey("$and")) {
            Object andValue = condition.get("$and");
            if (andValue instanceof List) {
                List<Map<String, Object>> conditions = (List<Map<String, Object>>) andValue;
                String andExpr = conditions.stream()
                    .map(cond -> parseConditionToNgql(prefix, cond))
                    .filter(expr -> expr != null && !expr.isEmpty())
                    .collect(Collectors.joining(" AND "));
                return andExpr.isEmpty() ? "" : "(" + andExpr + ")";
            }
        }
        // Check for $or
        else if (condition.containsKey("$or")) {
            Object orValue = condition.get("$or");
            if (orValue instanceof List) {
                List<Map<String, Object>> conditions = (List<Map<String, Object>>) orValue;
                String orExpr = conditions.stream()
                    .map(cond -> parseConditionToNgql(prefix, cond))
                    .filter(expr -> expr != null && !expr.isEmpty())
                    .collect(Collectors.joining(" OR "));
                return orExpr.isEmpty() ? "" : "(" + orExpr + ")";
            }
        }
        // Regular key-value conditions (implicit AND)
        else {
            List<String> exprs = condition.entrySet().stream()
                .map(entry -> buildSingleConditionNgql(prefix, entry.getKey(), entry.getValue()))
                .filter(expr -> expr != null && !expr.isEmpty())
                .collect(Collectors.toList());
            if (exprs.isEmpty()) {
                return "";
            } else if (exprs.size() == 1) {
                return exprs.get(0);
            } else {
                return String.join(" AND ", exprs);
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private String buildSingleConditionNgql(String prefix, String prop, Object value) {
        if (value instanceof Map) {
            Map<String, Object> opMap = (Map<String, Object>) value;
            for (Map.Entry<String, Object> opEntry : opMap.entrySet()) {
                String op = opEntry.getKey();
                Object opValue = opEntry.getValue();
                return buildOperatorCondition(prefix, prop, op, opValue);
            }
        }
        // Direct value
        return prefix + "." + prop + " == '" + escapeNgqlValue(value) + "'";
    }

    private String buildOperatorCondition(String prefix, String prop, String op, Object value) {
        String expr = prefix + "." + prop;
        return switch (op) {
            case "$eq" -> expr + " == '" + escapeNgqlValue(value) + "'";
            case "$ne" -> expr + " != '" + escapeNgqlValue(value) + "'";
            case "$gt" -> expr + " > " + escapeNgqlNumeric(value);
            case "$gte" -> expr + " >= " + escapeNgqlNumeric(value);
            case "$lt" -> expr + " < " + escapeNgqlNumeric(value);
            case "$lte" -> expr + " <= " + escapeNgqlNumeric(value);
            case "$in" -> {
                if (value instanceof List) {
                    String values = ((List<?>) value).stream()
                        .map(v -> "'" + escapeNgqlValue(v) + "'")
                        .collect(Collectors.joining(", "));
                    yield expr + " IN (" + values + ")";
                }
                yield "";
            }
            case "$nin" -> {
                if (value instanceof List) {
                    String values = ((List<?>) value).stream()
                        .map(v -> "'" + escapeNgqlValue(v) + "'")
                        .collect(Collectors.joining(", "));
                    yield expr + " NOT IN (" + values + ")";
                }
                yield "";
            }
            case "$contains" -> "CONTAINS(" + expr + ", '" + escapeNgqlValue(value) + "')";
            case "$startsWith" -> "STARTS WITH(" + expr + ", '" + escapeNgqlValue(value) + "')";
            case "$endsWith" -> "ENDS WITH(" + expr + ", '" + escapeNgqlValue(value) + "')";
            case "$between" -> {
                if (value instanceof List && ((List<?>) value).size() == 2) {
                    List<?> range = (List<?>) value;
                    yield expr + " >= " + escapeNgqlNumeric(range.get(0)) + " AND " + expr + " <= " + escapeNgqlNumeric(range.get(1));
                }
                yield "";
            }
            default -> "";
        };
    }

    private String escapeNgqlValue(Object value) {
        if (value == null) return "";
        return value.toString()
            .replace("\\", "\\\\")
            .replace("'", "\\'");
    }

    private String escapeNgqlNumeric(Object value) {
        if (value == null) return "0";
        return value.toString();
    }

    private String buildWhereClause(PathElement[] path) {
        List<String> conditions = new ArrayList<>();

        for (PathElement elem : path) {
            if (!elem.isEdge() && elem.getFilters() != null && !elem.getFilters().isEmpty()) {
                String varName = elem.getAs() != null ? elem.getAs() : elem.getType().toLowerCase();
                for (Map.Entry<String, Object> filter : elem.getFilters().entrySet()) {
                    conditions.add(varName + "." + filter.getKey() + " == '" + filter.getValue() + "'");
                }
            }
        }

        return String.join(" AND ", conditions);
    }
}
