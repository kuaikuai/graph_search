package com.jhk.graph.query;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filter 表达式构建器
 * 将统一 JSON filters 转换为 SPARQL 三元组模式或 FILTER 表达式
 *
 * 支持的操作符:
 * - 直接值: "name": "张三" → ?x baprop:name "张三" .
 * - $eq: "name": {"$eq": "张三"} → ?x baprop:name "张三" .
 * - $ne: "name": {"$ne": "张三"} → FILTER(?name != "张三")
 * - $gt: "age": {"$gt": 30} → FILTER(?age > 30)
 * - $gte: "age": {"$gte": 30} → FILTER(?age >= 30)
 * - $lt: "age": {"$lt": 30} → FILTER(?age < 30)
 * - $lte: "age": {"$lte": 30} → FILTER(?age <= 30)
 * - $in: "city": {"$in": ["北京", "上海"]} → FILTER(?city IN ("北京", "上海"))
 * - $nin: "city": {"$nin": ["广州"]} → FILTER(?city NOT IN ("广州"))
 * - $contains: "name": {"$contains": "张"} → FILTER(CONTAINS(?name, "张"))
 * - $startsWith: "name": {"$startsWith": "张"} → FILTER(STRSTARTS(?name, "张"))
 * - $endsWith: "name": {"$endsWith": "三"} → FILTER(STRENDS(?name, "三"))
 * - $between: "age": {"$between": [18, 65]} → FILTER(?age >= 18 && ?age <= 65)
 * - $and: {"$and": [{...}, {...}]} → FILTER(a && b && c)
 * - $or: {"$or": [{...}, {...}]} → FILTER(a || b || c)
 */
public class FilterExpressionBuilder {

    private static final Set<String> OPERATORS = Set.of(
        "$eq", "$ne", "$gt", "$gte", "$lt", "$lte", "$in", "$nin",
        "$contains", "$startsWith", "$endsWith", "$between", "$and", "$or"
    );

    /**
     * 判断是否为操作符格式的 filter value
     */
    public static boolean isOperatorFilter(Object value) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            return map.keySet().stream().anyMatch(k -> OPERATORS.contains(k.toString()));
        }
        return false;
    }

    /**
     * 解析 filters map，为每个 filter 生成对应的 SPARQL 语句片段
     *
     * @param filters 原始 filters map
     * @param subjectVar SPARQL 变量名，如 "?x"
     * @return 生成的 SPARQL 片段列表
     */
    public static FilterResult parseFilters(Map<String, Object> filters, String subjectVar) {
        FilterResult result = new FilterResult();
        if (filters == null || filters.isEmpty()) {
            return result;
        }

        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String prop = entry.getKey();
            Object value = entry.getValue();
            parseFilterEntry(result, prop, value, subjectVar);
        }

        return result;
    }

    /**
     * 解析单个 filter 条目
     */
    @SuppressWarnings("unchecked")
    private static void parseFilterEntry(FilterResult result, String prop, Object value, String subjectVar) {
        if ("$and".equals(prop)) {
            // $and: array of conditions, all must match (AND)
            if (value instanceof List) {
                List<Map<String, Object>> conditions = (List<Map<String, Object>>) value;
                String andExpr = conditions.stream()
                    .map(cond -> parseConditionAsFilter(cond, subjectVar))
                    .filter(expr -> expr != null && !expr.isEmpty())
                    .collect(Collectors.joining(" && "));
                if (!andExpr.isEmpty()) {
                    result.addFilter("  FILTER(" + andExpr + ")\n");
                }
            }
        } else if ("$or".equals(prop)) {
            // $or: array of conditions, any can match (OR)
            if (value instanceof List) {
                List<Map<String, Object>> conditions = (List<Map<String, Object>>) value;
                String orExpr = conditions.stream()
                    .map(cond -> parseConditionAsFilter(cond, subjectVar))
                    .filter(expr -> expr != null && !expr.isEmpty())
                    .collect(Collectors.joining(" || "));
                if (!orExpr.isEmpty()) {
                    result.addFilter("  FILTER(" + orExpr + ")\n");
                }
            }
        } else if (value instanceof Map) {
            Map<String, Object> opMap = (Map<String, Object>) value;
            // Check if it's a $and/$or nested condition
            if (opMap.containsKey("$and")) {
                parseFilterEntry(result, "$and", opMap.get("$and"), subjectVar);
            } else if (opMap.containsKey("$or")) {
                parseFilterEntry(result, "$or", opMap.get("$or"), subjectVar);
            } else {
                // Regular operators
                for (Map.Entry<String, Object> opEntry : opMap.entrySet()) {
                    String op = opEntry.getKey();
                    Object opValue = opEntry.getValue();
                    String filter = buildOperatorFilter(prop, op, opValue, subjectVar);
                    if (filter != null) {
                        result.addFilter(filter);
                    }
                }
            }
        } else {
            // 直接值 → 三元组模式
            String triple = String.format("  %s baprop:%s \"%s\" .\n", subjectVar, prop, escapeValue(value));
            result.addTriple(triple);
        }
    }

    /**
     * 解析条件（可能是简单值或嵌套的 $and/$or）并返回 SPARQL filter 表达式
     */
    @SuppressWarnings("unchecked")
    private static String parseConditionAsFilter(Map<String, Object> condition, String subjectVar) {
        if (condition == null || condition.isEmpty()) {
            return null;
        }

        // Check for nested $and/$or
        if (condition.containsKey("$and")) {
            Object andValue = condition.get("$and");
            if (andValue instanceof List) {
                List<Map<String, Object>> conditions = (List<Map<String, Object>>) andValue;
                String andExpr = conditions.stream()
                    .map(cond -> parseConditionAsFilter(cond, subjectVar))
                    .filter(expr -> expr != null && !expr.isEmpty())
                    .collect(Collectors.joining(" && "));
                return andExpr.isEmpty() ? null : "(" + andExpr + ")";
            }
        } else if (condition.containsKey("$or")) {
            Object orValue = condition.get("$or");
            if (orValue instanceof List) {
                List<Map<String, Object>> conditions = (List<Map<String, Object>>) orValue;
                String orExpr = conditions.stream()
                    .map(cond -> parseConditionAsFilter(cond, subjectVar))
                    .filter(expr -> expr != null && !expr.isEmpty())
                    .collect(Collectors.joining(" || "));
                return orExpr.isEmpty() ? null : "(" + orExpr + ")";
            }
        } else {
            // Simple key-value conditions (implicit AND within this object)
            List<String> exprs = condition.entrySet().stream()
                .map(entry -> buildSingleConditionFilter(entry.getKey(), entry.getValue(), subjectVar))
                .filter(expr -> expr != null && !expr.isEmpty())
                .collect(Collectors.toList());
            if (exprs.isEmpty()) {
                return null;
            } else if (exprs.size() == 1) {
                return exprs.get(0);
            } else {
                return "(" + String.join(" && ", exprs) + ")";
            }
        }
        return null;
    }

    /**
     * 为单个属性构建 filter 表达式（不含括号）
     */
    @SuppressWarnings("unchecked")
    private static String buildSingleConditionFilter(String prop, Object value, String subjectVar) {
        if (value instanceof Map) {
            Map<String, Object> opMap = (Map<String, Object>) value;
            for (Map.Entry<String, Object> opEntry : opMap.entrySet()) {
                String op = opEntry.getKey();
                Object opValue = opEntry.getValue();
                String filter = buildOperatorFilter(prop, op, opValue, subjectVar);
                if (filter != null) {
                    // Extract the FILTER content
                    String trimmed = filter.trim();
                    if (trimmed.startsWith("FILTER(") && trimmed.endsWith(")\n")) {
                        return trimmed.substring(7, trimmed.length() - 2);
                    }
                }
            }
        } else {
            // Direct value: ?x baprop:prop "value"
            return subjectVar + " baprop:" + prop + " \"" + escapeValue(value) + "\"";
        }
        return null;
    }

    /**
     * 构建操作符 filter 表达式
     */
    @SuppressWarnings("unchecked")
    private static String buildOperatorFilter(String prop, String op, Object value, String subjectVar) {
        String var = subjectVar + " baprop:" + prop;

        return switch (op) {
            case "$eq" -> {
                // $eq 等同于直接值
                yield String.format("  FILTER(%s = \"%s\")\n", var, escapeValue(value));
            }
            case "$ne" -> {
                // FILTER(?prop != "value")
                yield String.format("  FILTER(%s != \"%s\")\n", var, escapeValue(value));
            }
            case "$gt" -> {
                // FILTER(?prop > value)
                yield String.format("  FILTER(%s > %s)\n", var, escapeNumeric(value));
            }
            case "$gte" -> {
                // FILTER(?prop >= value)
                yield String.format("  FILTER(%s >= %s)\n", var, escapeNumeric(value));
            }
            case "$lt" -> {
                // FILTER(?prop < value)
                yield String.format("  FILTER(%s < %s)\n", var, escapeNumeric(value));
            }
            case "$lte" -> {
                // FILTER(?prop <= value)
                yield String.format("  FILTER(%s <= %s)\n", var, escapeNumeric(value));
            }
            case "$in" -> {
                // FILTER(?prop IN ("a", "b", "c"))
                if (value instanceof List) {
                    String values = ((List<?>) value).stream()
                        .map(v -> "\"" + escapeValue(v) + "\"")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                    yield String.format("  FILTER(%s IN (%s))\n", var, values);
                }
                yield null;
            }
            case "$nin" -> {
                // FILTER(?prop NOT IN ("a", "b", "c"))
                if (value instanceof List) {
                    String values = ((List<?>) value).stream()
                        .map(v -> "\"" + escapeValue(v) + "\"")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                    yield String.format("  FILTER(%s NOT IN (%s))\n", var, values);
                }
                yield null;
            }
            case "$contains" -> {
                // FILTER(CONTAINS(?prop, "value"))
                yield String.format("  FILTER(CONTAINS(%s, \"%s\"))\n", var, escapeValue(value));
            }
            case "$startsWith" -> {
                // FILTER(STRSTARTS(?prop, "value"))
                yield String.format("  FILTER(STRSTARTS(%s, \"%s\"))\n", var, escapeValue(value));
            }
            case "$endsWith" -> {
                // FILTER(STRENDS(?prop, "value"))
                yield String.format("  FILTER(STRENDS(%s, \"%s\"))\n", var, escapeValue(value));
            }
            case "$between" -> {
                // FILTER(?prop >= min && ?prop <= max)
                if (value instanceof List && ((List<?>) value).size() == 2) {
                    List<?> range = (List<?>) value;
                    Object min = range.get(0);
                    Object max = range.get(1);
                    yield String.format("  FILTER(%s >= %s && %s <= %s)\n",
                        var, escapeNumeric(min), var, escapeNumeric(max));
                }
                yield null;
            }
            case "$and", "$or" -> {
                // $and/$or should be handled at parseFilterEntry level, not here
                yield null;
            }
            default -> null;
        };
    }

    /**
     * 转义 SPARQL 字符串值
     */
    private static String escapeValue(Object value) {
        if (value == null) return "";
        return value.toString()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    /**
     * 处理数值类型
     */
    private static String escapeNumeric(Object value) {
        if (value == null) return "0";
        return value.toString();
    }

    /**
     * Filter 结果容器
     */
    public static class FilterResult {
        private final StringBuilder triples = new StringBuilder();
        private final StringBuilder filters = new StringBuilder();

        public void addTriple(String triple) {
            triples.append(triple);
        }

        public void addFilter(String filter) {
            filters.append(filter);
        }

        /**
         * 获取所有三元组模式
         */
        public String getTriples() {
            return triples.toString();
        }

        /**
         * 获取所有 FILTER 表达式
         */
        public String getFilters() {
            return filters.toString();
        }

        /**
         * 判断是否有 FILTER 表达式
         */
        public boolean hasFilters() {
            return !filters.isEmpty();
        }

        /**
         * 判断是否有三元组模式
         */
        public boolean hasTriples() {
            return !triples.isEmpty();
        }

        /**
         * 获取完整 SPARQL 片段（包含三元组和 filter）
         */
        public String toSPARQL() {
            StringBuilder sb = new StringBuilder();
            sb.append(triples);
            sb.append(filters);
            return sb.toString();
        }
    }
}
