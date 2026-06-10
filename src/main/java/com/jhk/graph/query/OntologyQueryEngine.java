package com.jhk.graph.query;

import org.apache.jena.rdf.model.*;
import org.apache.jena.reasoner.*;
import org.apache.jena.util.FileManager;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.ArrayDeque;
import org.apache.jena.query.*;
import org.apache.jena.query.ResultSetFactory;
import org.apache.jena.query.ResultSetRewindable;
import java.net.http.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 本体路径查询引擎
 * 基于本体定义自动查找路径锚点到目标类的路径，生成 SPARQL 查询
 */
public class OntologyQueryEngine {

    // 命名空间常量
    private static final String NS_CLASS   = "http://www.jhk.com/finance/business-analysis/class/";
    private static final String NS_PROP    = "http://www.jhk.com/finance/business-analysis/property/";
    private static final String PREFIX_BACLS  = "bacls";
    private static final String PREFIX_BAPROP = "baprop";
    private static final int DEFAULT_MAX_DEPTH = 6;
    private static final int DEFAULT_MAX_PATHS = 10;

    // 实例字段
    private final Model model;
    private final String endpoint;

    /**
     * 加载本体文件（TTL/RDF），不连接远程端点（仅用于本地测试/推理）
     * @param ontologyPath 本体文件路径（classpath 或绝对路径）
     * @throws IOException 文件不存在或加载失败
     */
    public OntologyQueryEngine(String ontologyPath) throws IOException {
        this(ontologyPath, null);
    }

    /**
     * 加载本体文件（TTL/RDF）并指定远程 SPARQL 端点
     * @param ontologyPath 本体文件路径（classpath 或绝对路径）
     * @param endpoint 远程 SPARQL 端点 URL（如 "http://localhost:3030/db/sparql"），可为 null
     * @throws IOException 文件不存在或加载失败
     */
    public OntologyQueryEngine(String ontologyPath, String endpoint) throws IOException {
        this.endpoint = endpoint;

        // 尝试从文件系统加载
        Model rawModel = ModelFactory.createDefaultModel();

        InputStream in = null;
        try {
            // 先尝试绝对路径
            Path path = Paths.get(ontologyPath);
            if (Files.exists(path)) {
                in = Files.newInputStream(path);
            } else {
                // 尝试 classpath
                in = FileManager.getInternal().open(ontologyPath);
            }

            if (in == null) {
                throw new IOException("本体文件不存在: " + ontologyPath);
            }

            rawModel.read(in, null, "TURTLE");
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException e) { /* ignore */ }
            }
        }

        if (rawModel.isEmpty()) {
            throw new IOException("本体文件为空或解析失败: " + ontologyPath);
        }

        // 创建带 RDFS 推理的 InfModel
        InfModel infModel = ModelFactory.createInfModel(
            ReasonerRegistry.getRDFSReasoner(), rawModel);
        this.model = infModel;
    }

    /**
     * 获取当前的 Model（供测试用）
     */
    public Model getModel() {
        return model;
    }

    // ==================== 路径搜索 ====================

    /**
     * 查找所有对象属性，其 range == currentClass
     * 返回 [(property, domainClass), ...]
     */
    private List<NodePair> getIncomingEdges(Resource currentClass) {
        String sparql = """
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            SELECT ?prop ?domainClass WHERE {
                ?prop rdfs:range <%s> ;
                      rdfs:domain ?domainClass .
            }
            """.formatted(currentClass.getURI());

        List<NodePair> edges = new ArrayList<>();
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                edges.add(new NodePair(
                    sol.getResource("prop"),
                    sol.getResource("domainClass")
                ));
            }
        }
        return edges;
    }

    /**
     * 反向 BFS：从目标类出发，逆向遍历本体对象属性
     * 构建所有可达类的父节点映射
     * @param targetClass 目标类 Resource
     * @param maxDepth 最大搜索深度
     * @return Map<childClass, ParentNode(parentClass, property, depth)>
     */
    Map<Resource, ParentNode> reverseBfs(Resource targetClass, int maxDepth) {
        Map<Resource, ParentNode> parentMap = new HashMap<>();
        Set<Resource> visited = new HashSet<>();
        Deque<Resource> queue = new ArrayDeque<>();
        queue.add(targetClass);
        visited.add(targetClass);

        while (!queue.isEmpty()) {
            Resource current = queue.poll();
            int currentDepth = 0;
            ParentNode currentNode = parentMap.get(current);
            if (currentNode != null) {
                currentDepth = currentNode.depth();
            }
            if (currentDepth >= maxDepth) continue;

            for (NodePair edge : getIncomingEdges(current)) {
                Resource parentClass = edge.domainClass();
                if (!visited.contains(parentClass)) {
                    int depth = currentDepth + 1;
                    parentMap.put(parentClass, new ParentNode(current, edge.prop(), depth));
                    visited.add(parentClass);
                    queue.add(parentClass);
                }
            }
        }
        return parentMap;
    }

    /**
     * 内部类：属性-域节点对
     */
    private record NodePair(Resource prop, Resource domainClass) {}

    // ==================== 路径子图构建 ====================

    /**
     * 从父节点映射构建路径子图
     * @param parentMap reverseBFS 构建的父节点映射
     * @param anchorClasses 锚点类集合
     * @return 路径子图
     */
    PathGraph buildPathGraph(Map<Resource, ParentNode> parentMap, Set<Resource> anchorClasses) {
        PathGraph graph = new PathGraph();

        if (parentMap.isEmpty() || anchorClasses.isEmpty()) return graph;

        // 对每个锚点，从锚点沿 parentMap 反向追溯到根（PainPoint 或未收录的节点）
        for (Resource anchor : anchorClasses) {
            if (!parentMap.containsKey(anchor)) continue; // 锚点不可达则跳过

            // 构建根→锚点的有序路径（正向）
            List<GraphNode> forwardPath = new ArrayList<>();
            Resource root = anchor;

            while (true) {
                ParentNode pn = parentMap.get(root);
                forwardPath.add(new GraphNode(root, pn != null ? pn.property() : null, new ArrayList<>(), root.equals(anchor)));
                if (pn == null) break;
                root = pn.parentClass();
            }

            // 翻转用于 mainPath（mainPath 期望根在前，节点从根到锚）
            List<GraphNode> reversed = new ArrayList<>();
            for (int i = forwardPath.size() - 1; i >= 0; i--) {
                reversed.add(forwardPath.get(i));
            }

            // 追加到主路径
            for (GraphNode node : reversed) {
                graph.addMainNode(node);
            }
        }

        return graph;
    }

    // ==================== 辅助方法 ====================

    /**
     * 类简单名 → Resource（完整 URI）
     * @param simpleName 类名如 "Organization"
     * @return 完整 URI 的 Resource
     */
    public Resource classUri(String simpleName) {
        return model.createResource(NS_CLASS + simpleName);
    }

    /**
     * 类名 → SPARQL 变量名
     * 变量命名策略：
     * - MetricDimensionUnit → "?mdu"
     * - AbnormalRule → "?rule"
     * - PainPoint → "?target"
     * - 其他类 → "? + 首字母小写类名"
     * @param className 类名
     * @return SPARQL 变量名（含 ? 前缀）
     */
    String varName(String className) {
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

    // ==================== SPARQL 生成 ====================

    /**
     * 生成 SPARQL 查询语句（不执行）
     * @param constraints      锚点约束 {classSimpleName: [instanceUri, ...]}
     * @param targetClass     目标类简单名称（如 "PainPoint"）
     * @param targetProperties 目标类的 datatype property 简称列表（可为空）
     * @return SPARQL 查询字符串（含 PREFIX 声明）
     * @throws IllegalArgumentException 类名不存在或锚点不可达
     */
    public String buildQuery(
        Map<String, List<String>> constraints,
        String targetClass,
        List<String> targetProperties
    ) {
        // 1. 解析锚点类
        Set<Resource> anchorClasses = new HashSet<>();
        for (String className : constraints.keySet()) {
            anchorClasses.add(classUri(className));
        }

        // 2. 反向 BFS 查找路径
        Resource targetUri = classUri(targetClass);
        Map<Resource, ParentNode> parentMap = reverseBfs(targetUri, DEFAULT_MAX_DEPTH);

        // 3. 构建路径子图（不移除验证，只要有一个锚点可达即可）
        // 注意：当锚点类不在图中时会自然产生空结果，不抛异常
        PathGraph graph = buildPathGraph(parentMap, anchorClasses);

        // 5. 生成 SPARQL
        return generateSparql(graph, parentMap, anchorClasses, constraints, targetClass, targetProperties);
    }

    /**
     * 生成完整 SPARQL 查询
     */
    private String generateSparql(
        PathGraph graph,
        Map<Resource, ParentNode> parentMap,
        Set<Resource> anchorClasses,
        Map<String, List<String>> constraints,
        String targetClass,
        List<String> targetProperties
    ) {
        StringBuilder sb = new StringBuilder();

        // PREFIX 声明
        sb.append(buildPrefixSection());

        // SELECT 子句
        sb.append("SELECT DISTINCT ");
        if (targetProperties != null && !targetProperties.isEmpty()) {
            sb.append("?target");
            for (String prop : targetProperties) {
                sb.append(" ?").append(prop);
            }
        } else {
            sb.append("?target");
        }
        sb.append(" WHERE {\n");

        // VALUES 子句
        sb.append(buildValuesSection(constraints));

        // 路径三元组
        sb.append(buildPathSection(graph, parentMap, anchorClasses));

        // 目标属性三元组
        if (targetProperties != null && !targetProperties.isEmpty()) {
            sb.append(buildTargetSection("target", targetProperties));
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 生成 PREFIX 声明
     */
    private String buildPrefixSection() {
        return """
            PREFIX bacls: <http://www.jhk.com/finance/business-analysis/class/>
            PREFIX baprop: <http://www.jhk.com/finance/business-analysis/property/>

            """;
    }

    /**
     * 生成 VALUES 子句
     */
    private String buildValuesSection(Map<String, List<String>> constraints) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : constraints.entrySet()) {
            String var = varName(entry.getKey());
            sb.append("  VALUES ").append(var).append(" { ");
            for (String uri : entry.getValue()) {
                sb.append("<").append(uri).append("> ");
            }
            sb.append("}\n");
        }
        return sb.toString();
    }

    /**
     * 生成路径三元组
     */
    private String buildPathSection(PathGraph graph, Map<Resource, ParentNode> parentMap, Set<Resource> anchorClasses) {
        StringBuilder sb = new StringBuilder();
        List<GraphNode> nodes = graph.mainPath;

        for (int i = 0; i < nodes.size() - 1; i++) {
            GraphNode current = nodes.get(i);
            GraphNode next = nodes.get(i + 1);

            String fromVar = varName(getSimpleName(current.cls()));
            String toVar = varName(getSimpleName(next.cls()));
            // 确定边的属性：对于非锚点的 next 节点，使用 next 在 parentMap 中的 entry
            // 对于锚点的 next 节点，也使用 parentMap 中的 entry（锚点也在 parentMap 中）
            // 如果 propRes 为空（根节点），则使用 next 的 property
            Resource propRes;
            ParentNode pnNext = parentMap.get(next.cls());
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

        // 处理侧分支
        for (Map.Entry<Resource, List<GraphNode>> entry : graph.sideBranches.entrySet()) {
            Resource parent = entry.getKey();
            String parentVar = varName(getSimpleName(parent));
            for (GraphNode branch : entry.getValue()) {
                String branchVar = varName(getSimpleName(branch.cls()));
                String propPrefix = getPropertyPrefix(branch.property());
                sb.append("  ").append(parentVar).append(" ");
                sb.append(propPrefix).append(":").append(getSimpleName(branch.property()));
                sb.append(" ").append(branchVar).append(" .\n");
            }
        }

        return sb.toString();
    }

    /**
     * 生成目标属性三元组
     */
    private String buildTargetSection(String targetVar, List<String> properties) {
        StringBuilder sb = new StringBuilder();
        for (String prop : properties) {
            sb.append("  ?").append(targetVar).append(" baprop:").append(prop);
            sb.append(" ?").append(prop).append(" .\n");
        }
        return sb.toString();
    }

    // ==================== 查询执行 ====================

    /**
     * 生成 SPARQL 并执行，返回结果
     * 内部调用 buildQuery + 执行
     * @param constraints      锚点约束
     * @param targetClass      目标类简单名称
     * @param targetProperties 目标类的 datatype property 简称列表（可为空）
     * @return 结果列表，每条包含 "target" 键（URI）和属性值键
     * @throws RuntimeException SPARQL 执行失败
     */
    public List<Map<String, String>> query(
        Map<String, List<String>> constraints,
        String targetClass,
        List<String> targetProperties
    ) {
        String sparql = buildQuery(constraints, targetClass, targetProperties);

        try {
            Query query = QueryFactory.create(sparql);
            try (QueryExecution qe = QueryExecutionFactory.create(query, model)) {
                ResultSet rs = qe.execSelect();
                return parseResults(rs, "target", targetProperties);
            }
        } catch (Exception e) {
            throw new RuntimeException("SPARQL 执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 ResultSet 为 List<Map<String, String>>
     */
    private List<Map<String, String>> parseResults(
        ResultSet rs,
        String targetVar,
        List<String> properties
    ) {
        List<Map<String, String>> results = new ArrayList<>();
        ResultSetRewindable rsRewindable = ResultSetFactory.makeRewindable(rs);

        while (rsRewindable.hasNext()) {
            QuerySolution sol = rsRewindable.next();
            Map<String, String> row = new HashMap<>();

            // 添加 target
            Resource targetRes = sol.getResource(targetVar);
            if (targetRes != null) {
                row.put("target", targetRes.getURI());
            }

            // 添加其他属性
            if (properties != null) {
                for (String prop : properties) {
                    if (sol.contains(prop)) {
                        org.apache.jena.rdf.model.RDFNode node = sol.get(prop);
                        if (node != null && node.isLiteral()) {
                            row.put(prop, node.asLiteral().getString());
                        }
                    }
                }
            }

            results.add(row);
        }

        return results;
    }

    // ==================== 工具方法 ====================

    /**
     * 从 Resource 获取类简单名
     */
    private String getSimpleName(Resource res) {
        if (res == null) return null;
        String uri = res.getURI();
        if (uri == null) return null;
        int lastSlash = uri.lastIndexOf('/');
        return lastSlash >= 0 ? uri.substring(lastSlash + 1) : uri;
    }

    /**
     * 获取属性对应的前缀
     */
    private String getPropertyPrefix(Resource prop) {
        if (prop == null) return "baprop";
        String uri = prop.getURI();
        if (uri != null && uri.contains("/property/")) {
            return "baprop";
        }
        return "baprop";
    }

    // ==================== 公开工具方法（供 SparqlBackend 调用） ====================

    /**
     * 生成带内联 source filter 的完整 path SPARQL
     * 使用子查询：内层找 source 实例，外层从这些实例找路径到 target
     * @param sourceType  source 类名，如 "Organization"
     * @param sourceFilters source 过滤条件（如 name="研发部"）
     * @param targetType  target 类名，如 "PainPoint"
     * @return 完整 SPARQL 查询字符串
     */
    /**
     * 执行原始 SPARQL 查询（公开方法，供 SparqlBackend 调用）
     * 如果配置了远程端点，则向远程端点执行查询；否则使用本地 model
     */
    public List<Map<String, String>> executeRawQuery(String sparql) {
        List<Map<String, String>> results = new ArrayList<>();
        try {
            Query query = QueryFactory.create(sparql);

            QueryExecution qe;
            if (endpoint != null && !endpoint.isBlank()) {
                // 使用远程 SPARQL 端点 - 通过 HTTP POST
                results = executeRemoteQuery(sparql, query.getResultVars());
                return results;
            } else {
                // 使用本地 model
                qe = QueryExecutionFactory.create(query, model);
            }

            try {
                ResultSet rs = qe.execSelect();
                ResultSetRewindable rsRewindable = ResultSetFactory.makeRewindable(rs);
                while (rsRewindable.hasNext()) {
                    QuerySolution sol = rsRewindable.next();
                    Map<String, String> row = new HashMap<>();
                    for (String varName : query.getResultVars()) {
                        if (sol.contains(varName)) {
                            RDFNode node = sol.get(varName);
                            if (node != null && node.isResource()) {
                                row.put(varName, node.asResource().getURI());
                            } else if (node != null && node.isLiteral()) {
                                row.put(varName, node.asLiteral().getString());
                            }
                        }
                    }
                    results.add(row);
                }
            } finally {
                qe.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("SPARQL execution failed: " + e.getMessage(), e);
        }
        return results;
    }

    /**
     * 通过 HTTP POST 执行远程 SPARQL 查询
     * 使用 SPARQL Protocol 标准：POST query to endpoint with application/sparql-results+json
     */
    private List<Map<String, String>> executeRemoteQuery(String sparql, List<String> resultVars) throws Exception {
        System.out.println("=== Remote SPARQL Query ===");
        System.out.println("Endpoint: " + endpoint);
        System.out.println("SPARQL:\n" + sparql);

        List<Map<String, String>> results = new ArrayList<>();

        // 构建 form data
        String formData = "query=" + java.net.URLEncoder.encode(sparql, StandardCharsets.UTF_8);

        java.net.URL url = new java.net.URL(endpoint);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/sparql-results+json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(formData.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        System.out.println("HTTP Response Code: " + responseCode);

        if (responseCode != 200) {
            // 读取错误响应
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    errorResponse.append(line);
                }
                System.out.println("Error Response: " + errorResponse);
            }
            throw new RuntimeException("SPARQL endpoint returned HTTP " + responseCode);
        }

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            System.out.println("Response Length: " + response.length() + " chars");
            System.out.println("Response Preview: " + response.substring(0, Math.min(500, response.length())) + "...");
            results = parseSparqlJsonResults(response.toString(), resultVars);
            System.out.println("Parsed Results Count: " + results.size());
        }

        System.out.println("=== Remote Query Done ===\n");
        return results;
    }

    /**
     * 解析 SPARQL JSON 结果格式
     * 解析 {"results": {"bindings": [...]}} 结构
     */
    private List<Map<String, String>> parseSparqlJsonResults(String json, List<String> resultVars) {
        List<Map<String, String>> results = new ArrayList<>();

        // 找到 bindings 数组的起始位置
        int bindingsPos = json.indexOf("\"bindings\"");
        if (bindingsPos < 0) return results;

        // 找到第一个 [ 之后的 ]
        int arrayStart = json.indexOf("[", bindingsPos);
        if (arrayStart < 0) return results;

        // 找到匹配的 ] - 需要正确处理嵌套括号
        int arrayEnd = findMatchingBracket(json, arrayStart);
        if (arrayEnd < 0) return results;

        // 提取 bindings 数组内容
        String bindingsContent = json.substring(arrayStart + 1, arrayEnd);
        System.out.println("Bindings content length: " + bindingsContent.length());

        // 逐个解析 binding 对象
        int pos = 0;
        while (pos < bindingsContent.length()) {
            // 跳过空白
            while (pos < bindingsContent.length() && Character.isWhitespace(bindingsContent.charAt(pos))) {
                pos++;
            }
            if (pos >= bindingsContent.length()) break;

            // 检查是否是 {
            if (bindingsContent.charAt(pos) != '{') {
                pos++;
                continue;
            }

            // 找到匹配的 }
            int objEnd = findMatchingBracket(bindingsContent, pos);
            if (objEnd < 0) break;

            String obj = bindingsContent.substring(pos, objEnd + 1);
            Map<String, String> row = new HashMap<>();

            // 解析每个变量
            for (String varName : resultVars) {
                // 查找 "varName" : { ... "value" : "..." }
                int varPos = obj.indexOf("\"" + varName + "\"");
                if (varPos >= 0) {
                    // 找到 { 开始
                    int braceStart = obj.indexOf("{", varPos);
                    if (braceStart >= 0) {
                        int braceEnd = obj.indexOf("}", braceStart);
                        if (braceEnd >= 0) {
                            String varObj = obj.substring(braceStart, braceEnd + 1);
                            // 在 varObj 中找 "value" : "..."
                            int valuePos = varObj.indexOf("\"value\"");
                            if (valuePos >= 0) {
                                int colon = varObj.indexOf(":", valuePos);
                                int firstQuote = varObj.indexOf("\"", colon + 1);
                                int secondQuote = varObj.indexOf("\"", firstQuote + 1);
                                if (firstQuote >= 0 && secondQuote > firstQuote) {
                                    String value = varObj.substring(firstQuote + 1, secondQuote);
                                    row.put(varName, value);
                                }
                            }
                        }
                    }
                }
            }

            if (!row.isEmpty()) {
                results.add(row);
            }

            pos = objEnd + 1;
        }

        return results;
    }

    /**
     * 找到与给定位置的开括号匹配的闭括号
     */
    private int findMatchingBracket(String str, int openPos) {
        char openChar = str.charAt(openPos);
        char closeChar = (openChar == '{') ? '}' : (openChar == '[') ? ']' : (openChar == '(') ? ')' : ' ';
        if (closeChar == ' ') return -1;

        int depth = 1;
        boolean inString = false;

        for (int i = openPos + 1; i < str.length(); i++) {
            char c = str.charAt(i);

            if (c == '"' && (i == 0 || str.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }

            if (inString) continue;

            if (c == openChar) {
                depth++;
            } else if (c == closeChar) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 生成带内联 source filter 的完整 path SPARQL
     * 使用子查询：内层找 source 实例（带 filter 条件），外层从这些实例找路径到 target
     * @param sourceType  source 类名，如 "Organization"
     * @param sourceFilters source 过滤条件（如 name="研发部"）
     * @param targetType  target 类名，如 "PainPoint"
     * @param propertiesToFetch 要获取的属性列表，格式 "className.prop" 如 "PainPoint.painPointPainPointDesc"，
     *                         或 null/空 表示自动发现路径上所有节点的所有属性
     * @return 完整 SPARQL 查询字符串
     */
    public String buildPathQueryWithInlineSourceFilter(String sourceType, Map<String, Object> sourceFilters, String targetType, List<String> propertiesToFetch) {
        String sourceVar = varName(sourceType);  // e.g., "?org"

        // 子查询：找匹配 filter 的 source 实例，结果变量名与 buildQuery 的 VALUES 变量一致
        StringBuilder subq = new StringBuilder();
        subq.append("  ").append(sourceVar).append(" rdf:type bacls:").append(sourceType).append(" .\n");
        if (sourceFilters != null && !sourceFilters.isEmpty()) {
            // 使用 FilterExpressionBuilder 处理所有 filter 操作符
            FilterExpressionBuilder.FilterResult filterResult =
                FilterExpressionBuilder.parseFilters(sourceFilters, sourceVar);
            subq.append(filterResult.getTriples());
            subq.append(filterResult.getFilters());
        }

        // 使用与 buildQuery 完全相同的路径构建逻辑
        Resource targetUri = classUri(targetType);
        Map<Resource, ParentNode> parentMap = reverseBfs(targetUri, DEFAULT_MAX_DEPTH);
        Set<Resource> anchorClasses = new HashSet<>();
        anchorClasses.add(classUri(sourceType));
        PathGraph graph = buildPathGraph(parentMap, anchorClasses);

        // 自动发现路径上所有节点类型的属性（如果未指定）
        Map<String, List<String>> nodeProperties = new HashMap<>();
        if (propertiesToFetch == null || propertiesToFetch.isEmpty()) {
            // 自动发现：遍历路径上所有节点，查询每个节点类的 datatype properties
            for (GraphNode node : graph.mainPath) {
                String className = getSimpleName(node.cls());
                if (className != null) {
                    List<String> props = discoverDatatypeProperties(className);
                    if (!props.isEmpty()) {
                        nodeProperties.put(className, props);
                    }
                }
            }
        } else {
            // 解析指定的属性列表，格式 "className.prop"
            for (String propSpec : propertiesToFetch) {
                if (propSpec.contains(".")) {
                    String[] parts = propSpec.split("\\.",2);
                    String className = parts[0];
                    String propName = parts[1];
                    nodeProperties.computeIfAbsent(className, k -> new ArrayList<>()).add(propName);
                } else {
                    // 没有指定类名，默认添加到 target
                    nodeProperties.computeIfAbsent(targetType, k -> new ArrayList<>()).add(propSpec);
                }
            }
        }

        // 构建路径三元组片段（从 _source_ 出发）
        // mainPath 顺序：[PainPoint, rule, mdu, scenario, org]
        // 反向遍历 i=4,3,2,1：从 org 出发走向 PainPoint
        // 当前节点 current = mainPath[i], prev = mainPath[i-1]
        // 边 direction: current --prop--> prev（正向路径）
        List<GraphNode> nodes = graph.mainPath;
        StringBuilder pathTriples = new StringBuilder();
        for (int i = nodes.size() - 1; i > 0; i--) {
            GraphNode current = nodes.get(i);
            GraphNode prev = nodes.get(i - 1);

            // 优先用 current.property()（GraphNode 自带的属性，直接是 current→prev 的边）
            // 只有当 current.property() 为空时才用 parentMap 中反向的 property
            Resource propRes = current.property();
            if (propRes == null && parentMap.containsKey(current.cls())) {
                propRes = parentMap.get(current.cls()).property();
            }
            String propSimple = getSimpleName(propRes);
            String fromVar = varName(getSimpleName(current.cls()));
            String toVar = varName(getSimpleName(prev.cls()));

            pathTriples.append("  ").append(fromVar).append(" baprop:").append(propSimple).append(" ").append(toVar).append(" .\n");
        }

        // 生成节点属性三元组
        StringBuilder propTriples = new StringBuilder();
        StringBuilder selectClause = new StringBuilder("SELECT DISTINCT ?target");

        // 按路径顺序添加属性（从 source 到 target）
        for (GraphNode node : nodes) {
            String className = getSimpleName(node.cls());
            List<String> props = nodeProperties.get(className);
            if (props != null && !props.isEmpty()) {
                String nodeVar = varName(className);
                for (String prop : props) {
                    // 添加到 SELECT
                    selectClause.append(" ?").append(prop);
                    // 添加属性三元组
                    propTriples.append("  ").append(nodeVar).append(" baprop:").append(prop).append(" ?").append(prop).append(" .\n");
                }
            }
        }

        // 生成完整 SPARQL
        StringBuilder sb = new StringBuilder();
        sb.append("PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n");
        sb.append("PREFIX bacls: <http://www.jhk.com/finance/business-analysis/class/>\n");
        sb.append("PREFIX baprop: <http://www.jhk.com/finance/business-analysis/property/>\n\n");

        sb.append(selectClause).append(" WHERE {\n");
        sb.append("  {\n");
        sb.append("    SELECT DISTINCT ").append(sourceVar).append(" WHERE {\n");
        sb.append(subq);
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append(pathTriples);
        sb.append(propTriples);
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 检查实例 URI 是否存在
     * @param instanceUri 实例 URI
     * @return true if the instance exists
     */
    public boolean nodeExists(String instanceUri) {
        String sparql = """
            ASK WHERE { <%s> ?p ?o }
            """.formatted(instanceUri);

        try {
            Query query = QueryFactory.create(sparql);
            try (QueryExecution qe = QueryExecutionFactory.create(query, model)) {
                return qe.execAsk();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查指定类型的节点是否至少有一个匹配 filters 的实例
     * @param type 类名（如 "Organization"）
     * @param filters 过滤条件（可为 null）
     * @return true if at least one instance exists
     */
    public boolean typeHasMatchingInstance(String type, Map<String, Object> filters) {
        StringBuilder sparql = new StringBuilder();
        sparql.append("PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n");
        sparql.append("PREFIX bacls: <http://www.jhk.com/finance/business-analysis/class/>\n");
        sparql.append("PREFIX baprop: <http://www.jhk.com/finance/business-analysis/property/>\n\n");
        sparql.append("ASK WHERE {\n");
        sparql.append("  ?instance rdf:type bacls:").append(type).append(" .\n");

        if (filters != null && !filters.isEmpty()) {
            FilterExpressionBuilder.FilterResult filterResult =
                FilterExpressionBuilder.parseFilters(filters, "?instance");
            sparql.append(filterResult.getTriples());
        }

        sparql.append("}\n");

        try {
            Query query = QueryFactory.create(sparql.toString());
            try (QueryExecution qe = QueryExecutionFactory.create(query, model)) {
                return qe.execAsk();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取指定实例的所有属性值
     * @param instanceUri 实例 URI
     * @return 属性名到属性值的 map
     */
    public Map<String, String> getNodeProperties(String instanceUri) {
        Map<String, String> properties = new HashMap<>();

        String sparql = """
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX bacls: <http://www.jhk.com/finance/business-analysis/class/>
            PREFIX baprop: <http://www.jhk.com/finance/business-analysis/property/>

            SELECT ?prop ?value WHERE {
                <%s> ?prop ?value .
                FILTER(!IsBlank(?value))
            }
            """.formatted(instanceUri);

        try {
            Query query = QueryFactory.create(sparql);
            try (QueryExecution qe = QueryExecutionFactory.create(query, model)) {
                ResultSet rs = qe.execSelect();
                ResultSetRewindable rsRewindable = ResultSetFactory.makeRewindable(rs);
                while (rsRewindable.hasNext()) {
                    QuerySolution sol = rsRewindable.next();
                    RDFNode propNode = sol.get("prop");
                    RDFNode valueNode = sol.get("value");

                    if (propNode != null && valueNode != null) {
                        // 获取属性简称
                        String propUri = propNode.asResource().getURI();
                        String propName = getSimpleName(propNode.asResource());

                        // 获取属性值
                        String value;
                        if (valueNode.isLiteral()) {
                            value = valueNode.asLiteral().getString();
                        } else if (valueNode.isResource()) {
                            value = valueNode.asResource().getURI();
                        } else {
                            value = valueNode.toString();
                        }

                        properties.put(propName, value);
                    }
                }
            }
        } catch (Exception e) {
            // 返回空属性 map
        }

        return properties;
    }

    /**
     * 自动发现指定类的所有 datatype property简称
     * @param className 类名（如 "PainPoint"）
     * @return 属性简称列表
     */
    public List<String> discoverDatatypeProperties(String className) {
        List<String> props = new ArrayList<>();
        String sparql = """
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            PREFIX bacls: <http://www.jhk.com/finance/business-analysis/class/>
            SELECT ?prop WHERE {
                ?prop a owl:DatatypeProperty ;
                      rdfs:domain bacls:%s .
            }
            """.formatted(className);

        try {
            Query query = QueryFactory.create(sparql);
            try (QueryExecution qe = QueryExecutionFactory.create(query, model)) {
                ResultSet rs = qe.execSelect();
                ResultSetRewindable rsRewindable = ResultSetFactory.makeRewindable(rs);
                while (rsRewindable.hasNext()) {
                    QuerySolution sol = rsRewindable.next();
                    Resource propRes = sol.getResource("prop");
                    if (propRes != null) {
                        String propName = getSimpleName(propRes);
                        if (propName != null) {
                            props.add(propName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 返回空列表
        }
        return props;
    }
}
