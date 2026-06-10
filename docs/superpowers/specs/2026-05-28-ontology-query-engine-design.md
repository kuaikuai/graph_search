# 本体路径查询引擎 - 设计规格

**日期**: 2026-05-28
**更新**: 2026-06-08
**主题**: 基于本体的 SPARQL 查询路径生成引擎
**语言**: Java
**框架**: Spring Boot + Apache Jena
**状态**: 已实现

---

## 目录

1. [依赖](#依赖)
2. [概述](#概述)
3. [核心概念：路径锚点](#核心概念路径锚点)
4. [核心流程](#核心流程)
5. [算法：反向 BFS](#算法反向-bfs)
6. [API 设计](#api-设计)
7. [命名空间](#命名空间)
8. [SPARQL 生成规范](#sparql-生成规范)
9. [内部数据类](#内部数据类)
10. [错误处理](#错误处理)
11. [结果格式](#结果格式)
12. [调用示例](#调用示例)
13. [文件结构](#文件结构)
14. [附录：完整类结构](#附录完整类结构)

---

## 依赖

```xml
<dependency>
    <groupId>org.apache.jena</groupId>
    <artifactId>jena-arq</artifactId>
    <version>6.1.0</version>
</dependency>
```

单一依赖即包含：TTL 解析（Rio）+ SPARQL 执行（ARQ）+ RDFS 推理（InfModel）。

---

## 概述

给定本体文件 + 多个路径锚点（类名-实例值约束）+ 目标类名 → 自动查找本体中的连通路径 → 生成带约束条件的 SPARQL 查询 → 返回目标实例列表。

### 核心功能

1. **本地本体推理**：加载 TTL 文件，通过 RDFS 推理机构建本体图
2. **远程 SPARQL 端点**：支持向远程 SPARQL 端点执行查询（通过 HTTP POST）
3. **内联过滤器查询**：source/target 的 filters 条件直接内联到 SPARQL WHERE 子句
4. **工具方法**：`nodeExists()`、`typeHasMatchingInstance()`、`getNodeProperties()` 等

---

## 核心概念：路径锚点

锚点可以落在路径的**任意位置**——起点、中间、终点、侧分支：

```
 Organization ──→ AnalysisScenario ──→ MetricDimensionUnit ──→ AbnormalRule ──→ PainPoint
     ↑ 锚点            ↑ 锚点               ↑ 锚点(侧分支)                        ↑ 目标
                                             Dimension
```

同类的多个实例值用 `VALUES` 子句约束。

---

## 核心流程

```
输入: 约束 Map<String, List<String>> + 目标类名(String)
      ↓
Step 1: 定位所有锚点类和目标类的 Resource 对象
      ↓
Step 2: 反向 BFS（从目标类出发，沿 range→domain 逆向遍历本体对象属性）
        → 构建所有锚点类的父节点映射
      ↓
Step 3: 从每个锚点类沿父节点链向目标回溯，合并共享路径段
      ↓
Step 4: 生成带 PREFIX + VALUES 的 SPARQL 查询
      ↓
Step 5: 执行查询，返回目标实例列表 + datatype property 值
```

---

## 算法：反向 BFS

从目标类出發，逆向遍历所有对象属性的 range→domain 方向，构建类到目标的可达路径。

### 搜索方向

```
 正向(domain→range): org → involvesScenario → scenario
 逆向(range→domain): scenario ← involvesScenario ← org
```

### Jena RDFS 提取：获取指向当前类的属性

```java
// 查找所有对象属性，其 range == currentClass
// 返回 [(property, domainClass), ...]
List<NodePair> getIncomingEdges(Resource currentClass) {
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
            edges.add(new NodePair(sol.getResource("prop"),
                                   sol.getResource("domainClass")));
        }
    }
    return edges;
}
```

### 算法伪代码

```
function reverseBfs(targetClass, maxDepth=6):
    parentMap = {}          // class → ParentNode(parentClass, property, depth)
    queue = [(targetClass, 0)]
    visited = set()

    while queue not empty:
        current, depth = queue.poll()
        if depth > maxDepth: continue

        for (prop, parentClass) in getIncomingEdges(current):
            if parentClass not in visited:
                parentMap[parentClass] = ParentNode(current, prop, depth + 1)
                queue.add((parentClass, depth + 1))
                visited.add(parentClass)

    // 验证所有锚点类可达（可选）
    for anchor in anchorClasses:
        if anchor not in parentMap: throw UnreachableException

    // 从锚点回溯目标，构建路径子图
    return buildPathGraph(parentMap, anchorClasses)
```

### 算法约束

**重要限制**：reverseBFS 只能查找**下游锚点**（即从目标类通过对象属性可达的方向）。

本体结构示例：
```
                            PainPoint(目标)
                                ↑
                        AbnormalRule
                                ↑
                        MetricDimensionUnit
                                ↑
    Organization ──→ AnalysisScenario ──→ ...
           ↑                                           ↑
        下游锚点(支持)                              上游锚点(不支持)
    AnalysisTopic ──→ ... ──→ PainPoint
           ↑
       上游锚点(不支持)
```

如果锚点（如 AnalysisTopic）在目标类的**上游**，reverseBFS 无法到达，将抛出 `IllegalArgumentException("Anchor 'X' is upstream of target, not reachable via reverse BFS")`。

如需支持上游锚点，需使用双向 BFS 扩展（暂不在本期范围内）。

---

## API 设计

### OntologyQueryEngine 公开接口

```java
public class OntologyQueryEngine {

    /**
     * 加载本体文件（TTL/RDF），不连接远程端点（仅用于本地测试/推理）
     * @param ontologyPath 本体文件路径（classpath 或绝对路径）
     * @throws IOException 文件不存在
     */
    public OntologyQueryEngine(String ontologyPath) throws IOException;

    /**
     * 加载本体文件（TTL/RDF）并指定远程 SPARQL 端点
     * @param ontologyPath 本体文件路径（classpath 或绝对路径）
     * @param endpoint 远程 SPARQL 端点 URL，可为 null
     * @throws IOException 文件不存在
     */
    public OntologyQueryEngine(String ontologyPath, String endpoint) throws IOException;

    /**
     * 生成 SPARQL 查询语句（不执行）
     * @param constraints      锚点约束 {classSimpleName: [instanceUri, ...]}
     * @param targetClass      目标类简单名称（如 "PainPoint"）
     * @param targetProperties 目标类的 datatype property 简称列表（可为空）
     * @return SPARQL 查询字符串（含 PREFIX 声明）
     */
    public String buildQuery(
        Map<String, List<String>> constraints,
        String targetClass,
        List<String> targetProperties
    );

    /**
     * 生成 SPARQL 并执行，返回结果
     * @return 结果列表，每条包含 "target" 键（URI）和属性值键
     */
    public List<Map<String, String>> query(
        Map<String, List<String>> constraints,
        String targetClass,
        List<String> targetProperties
    );

    /**
     * 执行原始 SPARQL 查询
     * 如果配置了远程端点，则向远程端点执行查询；否则使用本地 model
     */
    public List<Map<String, String>> executeRawQuery(String sparql);

    /**
     * 生成带内联 source filter 的完整 path SPARQL
     * 使用子查询：内层找 source 实例（带 filter 条件），外层从这些实例找路径到 target
     */
    public String buildPathQueryWithInlineSourceFilter(
        String sourceType,
        Map<String, Object> sourceFilters,
        String targetType
    );

    // ── 工具方法 ──

    public boolean nodeExists(String instanceUri);
    public boolean typeHasMatchingInstance(String type, Map<String, Object> filters);
    public Map<String, String> getNodeProperties(String instanceUri);
    public Resource classUri(String simpleName);  // 类简单名 → Resource
}
```

### GraphBackend 接口

```java
public interface GraphBackend {
    GraphQueryResponse execute(GraphQueryRequest request);
    String getType();
    String buildSparql(GraphQueryRequest request);  // 仅生成 SPARQL，不执行
}
```

### SparqlBackend 查询类型

```java
public class SparqlBackend implements GraphBackend {
    // path 查询：source → target 路径查找
    // traverse 查询：从起点 BFS 遍历
    // pattern 查询：vertices + edges 或 path数组模式匹配
}
```

---

## 命名空间

```java
private static final String NS_CLASS   = "http://www.jhk.com/finance/business-analysis/class/";
private static final String NS_PROP    = "http://www.jhk.com/finance/business-analysis/property/";

// SPARQL 前缀
private static final String PREFIX_BACLS  = "bacls";
private static final String PREFIX_BAPROP = "baprop";
```

---

## SPARQL 生成规范

### 变量命名策略

SPARQL 变量名按**锚点类名**生成，规则如下：

| 类名 | 变量名 | 说明 |
|------|--------|------|
| Organization | `?org` | 首字母小写驼峰 |
| AnalysisScenario | `?scenario` | 完整类名小写 |
| MetricDimensionUnit | `?mdu` | 缩写（约定俗成） |
| AbnormalRule | `?rule` | 缩写 |
| Dimension | `?dim` | 缩写 |
| PainPoint (目标) | `?target` | 固定为 `target` |
| 其他类 | `?` + lowercase(className) | 统一规则 |

```java
private String varName(String className) {
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
```

### 示例

输入:
```java
Map<String, List<String>> constraints = Map.of(
    "Organization", List.of("http://.../冰冷事业部"),
    "AnalysisScenario", List.of("http://.../营业收入", "http://.../销量"),
    "Dimension", List.of("http://.../内销", "http://.../线下")
);
String targetClass = "PainPoint";
```

输出:
```sparql
PREFIX bacls: <http://www.jhk.com/finance/business-analysis/class/>
PREFIX baprop: <http://www.jhk.com/finance/business-analysis/property/>

SELECT DISTINCT ?target ?desc WHERE {
  VALUES ?org      { <uriorg> }
  VALUES ?scenario { <uri场景1> <uri场景2> }
  VALUES ?dim      { <uri内销> <uri线下> }

  ?org      baprop:involvesScenario             ?scenario .
  ?scenario baprop:involvesMetricDimensionUnit  ?mdu .
  ?mdu      baprop:containsDimension            ?dim .
  ?mdu      baprop:hasAbnormalRule              ?rule .
  ?rule     baprop:correspondsToPainPoint       ?target .
  ?target   baprop:painPointPainPointDesc       ?desc .
}
```

### 带内联 Filter 的 Path SPARQL

当 source 有 filters 时，使用子查询结构：

```sparql
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX bacls: <http://www.jhk.com/finance/business-analysis/class/>
PREFIX baprop: <http://www.jhk.com/finance/business-analysis/property/>

SELECT DISTINCT ?target WHERE {
  {
    SELECT DISTINCT ?_source_ WHERE {
      ?_source_ rdf:type bacls:Organization .
      ?_source_ baprop:orgOrgName "研发部" .
    }
  }
  ?_source_ baprop:involvesScenario ?scenario .
  ?scenario baprop:involvesMetricDimensionUnit ?mdu .
  ?mdu baprop:hasAbnormalRule ?rule .
  ?rule baprop:correspondsToPainPoint ?target .
}
```

### targetProperties 参数说明

`targetProperties` 是**属性简称**（不含命名空间前缀），与本体中的 `rdfs:label` 对应：

| 传入值 | 实际属性 URI |
|--------|-------------|
| `"painPointPainPointDesc"` | `baprop:painPointPainPointDesc` |
| `"improveMeasureImproveMeasure"` | `baprop:improveMeasureImproveMeasure` |

生成逻辑：
```java
// targetProperties = ["painPointPainPointDesc"]
// 生成:
// SELECT DISTINCT ?target ?painPointPainPointDesc WHERE { ... }
//   ?target baprop:painPointPainPointDesc ?painPointPainPointDesc .
```

如果不传 `targetProperties`，SELECT 句仅返回 `?target`（目标 URI）。

### SPARQL 格式要求

- 必须使用 `PREFIX` 声明，不得使用完整 URI
- 同类多值使用 `VALUES` 子句
- 变量按上述"变量命名策略"生成

---

## 内部数据类

### ParentNode

```java
/**
 * 记录路径中指向当前类的父节点
 * @param parentClass 父节点类 Resource
 * @param property    链接父子类的属性 Resource
 * @param depth       从目标类到父节点的深度
 */
public record ParentNode(Resource parentClass, Resource property, int depth) {
    public ParentNode(Resource parentClass, Resource property) {
        this(parentClass, property, 0);
    }
}
```

### GraphNode

```java
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
}
```

### PathGraph

```java
/**
 * 路径子图
 * 包含主路径节点列表和侧分支节点映射
 */
public class PathGraph {
    /** 主路径节点列表（从锚点到目标的节点序列） */
    public final List<GraphNode> mainPath = new ArrayList<>();

    /** 侧分支节点映射: parentClass → [分支节点] */
    public final Map<Resource, List<GraphNode>> sideBranches = new HashMap<>();
}
```

---

## Filter表达式构建器

`FilterExpressionBuilder` 将统一 JSON filters 转换为 SPARQL 三元组模式或 FILTER 表达式。

### 支持的操作符

| 操作符 | 示例 | 说明 |
|--------|------|------|
| 直接值 | `"name": "张三"` | 等于 (eq) |
| $eq | `"name": { "$eq": "张三" }` | 等于 |
| $ne | `"name": { "$ne": "张三" }` | 不等于 |
| $gt | `"age": { "$gt": 30 }` | 大于 |
| $gte | `"age": { "$gte": 30 }` | 大于等于 |
| $lt | `"age": { "$lt": 30 }` | 小于 |
| $lte | `"age": { "$lte": 30 }` | 小于等于 |
| $in | `"city": { "$in": ["北京", "上海"] }` | 在列表中 |
| $nin | `"city": { "$nin": ["广州"] }` | 不在列表中 |
| $contains | `"name": { "$contains": "张" }` | 包含 |
| $startsWith | `"name": { "$startsWith": "张" }` | 开头匹配 |
| $endsWith | `"name": { "$endsWith": "三" }` | 结尾匹配 |
| $between | `"age": { "$between": [18, 65] }` | 范围 |

### FilterResult

```java
public static class FilterResult {
    private final StringBuilder triples = new StringBuilder();
    private final StringBuilder filters = new StringBuilder();

    public void addTriple(String triple) { triples.append(triple); }
    public void addFilter(String filter) { filters.append(filter); }
    public String getTriples() { return triples.toString(); }
    public String getFilters() { return filters.toString(); }
    public String toSPARQL() { return triples.toString() + filters.toString(); }
}
```

---

## 错误处理

| 场景 | 预期行为 |
|------|---------|
| 类名不存在 | `IllegalArgumentException("Unknown class: " + name)` |
| 锚点在上游（目标下游不可达） | `IllegalArgumentException("Anchor 'X' is upstream of target, not reachable via reverse BFS")` |
| 锚点不可达 | `RuntimeException("Anchor 'X' unreachable from target 'Y'")` |
| 本体文件不存在 | `IOException` (向上抛出) |
| SPARQL 执行失败 | `RuntimeException("SPARQL execution failed", cause)` |
| 无可用路径 | 返回空列表 `[]` |

### GraphQueryException 错误码

| code | HTTP状态 | 说明 |
|------|----------|------|
| INVALID_QUERY_TYPE | 400 | queryType 不支持 |
| INVALID_FILTER | 400 | 过滤条件格式错误 |
| INVALID_PATTERN | 400 | 路径模式描述错误 |
| VERTEX_NOT_FOUND | 404 | 起点/终点节点不存在 |
| EDGE_NOT_FOUND | 404 | 边不存在 |
| TIMEOUT | 408 | 查询超时 |
| BACKEND_ERROR | 500 | 后端图数据库错误 |
| INTERNAL_ERROR | 500 | 内部服务错误 |

---

## 结果格式

```java
List<Map<String, String>> results = engine.query(constraints, "PainPoint",
    List.of("painPointPainPointDesc"));

// results = [
//   {"target": "http://.../painpoint/xxx",
//    "painPointPainPointDesc": "某渠道销量同比下降超过30%"},
//   ...
// ]
```

- `"target"` 键固定返回目标实例 URI
- 额外属性键名由 `targetProperties` 参数决定

---

## 调用示例

### 直接使用 OntologyQueryEngine

```java
OntologyQueryEngine engine = new OntologyQueryEngine("my.ttl");

Map<String, List<String>> constraints = Map.of(
    "Organization", List.of(
        "http://www.jhk.com/finance/business-analysis/instance/organization/冰冷事业部"),
    "AnalysisScenario", List.of(
        "http://www.jhk.com/finance/business-analysis/instance/scenario/营业收入",
        "http://www.jhk.com/finance/business-analysis/instance/scenario/销量"),
    "Dimension", List.of(
        "http://www.jhk.com/finance/business-analysis/instance/dimension/内销",
        "http://www.jhk.com/finance/business-analysis/instance/dimension/线下")
);

// 仅获取查询语句（调试/预览）
String sparql = engine.buildQuery(constraints, "PainPoint",
    List.of("painPointPainPointDesc"));
System.out.println(sparql);

// 直接获取结果
List<Map<String, String>> results = engine.query(constraints, "PainPoint",
    List.of("painPointPainPointDesc"));
```

### 使用 SparqlBackend（通过 HTTP API）

```java
// POST /graph/{reponame}/query
{
  "queryType": "path",
  "source": {
    "type": "Organization",
    "filters": { "orgOrgName": "研发部" }
  },
  "target": { "type": "PainPoint" },
  "dryRun": false
}
```

响应：
```json
{
  "success": true,
  "data": {
    "queryType": "path",
    "paths": [
      {
        "nodes": [
          { "id": "http://.../org/1", "type": "Organization" },
          { "id": "http://.../scenario/1", "type": "AnalysisScenario" },
          { "id": "http://.../painpoint/1", "type": "PainPoint" }
        ],
        "edges": [...],
        "totalHops": 2
      }
    ],
    "totalPaths": 1
  }
}
```

---

## 文件结构

```
graph-search-api/
  pom.xml                         # Maven 配置（jena-arq + nebula-java）
  src/main/java/com/jhk/graph/
    GraphSearchApplication.java    # Javalin HTTP 服务器
    backend/
      GraphBackend.java            # 后端接口
      sparql/
        SparqlBackend.java         # SPARQL 后端实现
      nebula/
        NebulaBackend.java         # NebulaGraph 后端实现
        NebulaConnection.java # NebulaGraph 连接管理
        NgqlConverter.java        # JSON → nGQL 转换
    config/
      BackendConfig.java           # 配置加载
    query/
      OntologyQueryEngine.java     # 本体路径查询引擎
      ParentNode.java              # 父节点记录
      GraphNode.java              # 路径节点记录
      PathGraph.java              # 路径子图
      FilterExpressionBuilder.java # Filter 表达式构建器
      WhereClauseValidator.java   # WHERE 子句验证器
    dto/
      request/
        GraphQueryRequest.java    # 查询请求 DTO
        SourceTarget.java         # 源/目标节点定义
        PathElement.java          # 路径元素（legacy）
        PatternVertex.java        # 模式顶点定义
        PatternEdge.java          # 模式边定义
      response/
        ApiResponse.java          # 统一 API 响应
        GraphQueryResponse.java   # 图查询响应
    exception/
      GraphQueryException.java    # 查询异常
  src/test/java/com/jhk/graph/
    backend/
      sparql/
        SparqlBackendTest.java # SPARQL 后端测试
        PathQueryFilterTest.java # Path 查询过滤器测试
      nebula/
        NgqlConverterTest.java # nGQL 转换测试
  src/main/resources/
    config.yaml                   # 配置文件
    my.ttl                        # 本体文件
```

---

## 附录：完整类结构

### OntologyQueryEngine 内部方法

```java
public class OntologyQueryEngine {

    // ── 常量 ──
    private static final String NS_CLASS   = "http://www.jhk.com/finance/business-analysis/class/";
    private static final String NS_PROP    = "http://www.jhk.com/finance/business-analysis/property/";
    private static final String PREFIX_BACLS  = "bacls";
    private static final String PREFIX_BAPROP = "baprop";
    private static final int DEFAULT_MAX_DEPTH = 6;
    private static final int DEFAULT_MAX_PATHS = 10;

    // ── 字段 ──
    private final Model model;   // InfModel（带 RDFS 推理）
    private final String endpoint;  // 远程 SPARQL 端点（可为 null）

    // ── 公开接口 ──
    public OntologyQueryEngine(String ontologyPath) throws IOException;
    public OntologyQueryEngine(String ontologyPath, String endpoint) throws IOException;
    public String buildQuery(Map<String, List<String>> constraints,
                             String targetClass,
                             List<String> targetProperties);
    public List<Map<String, String>> query(Map<String, List<String>> constraints,
                                            String targetClass,
                                            List<String> targetProperties);
    public List<Map<String, String>> executeRawQuery(String sparql);
    public String buildPathQueryWithInlineSourceFilter(String sourceType,
                                                         Map<String, Object> sourceFilters,
                                                         String targetType);
    public boolean nodeExists(String instanceUri);
    public boolean typeHasMatchingInstance(String type, Map<String, Object> filters);
    public Map<String, String> getNodeProperties(String instanceUri);
    public Resource classUri(String simpleName);
    public Model getModel();  // 供测试用

    // ── 内部处理 ──
    String varName(String className);             // 类名 → SPARQL 变量名

    // ── 路径搜索 ──
    Map<Resource, ParentNode> reverseBfs(Resource targetClass, int maxDepth);
    PathGraph buildPathGraph(Map<Resource, ParentNode> parentMap,
                              Set<Resource> anchorClasses);

    // ── SPARQL 生成 ──
    private String generateSparql(PathGraph graph,
                                   Map<Resource, ParentNode> parentMap,
                                   Set<Resource> anchorClasses,
                                   Map<String, List<String>> constraints,
                                   String targetClass,
                                   List<String> targetProperties);
    private String buildPrefixSection();
    private String buildValuesSection(Map<String, List<String>> constraints);
    private String buildPathSection(PathGraph graph, Map<Resource, ParentNode> parentMap,
                                     Set<Resource> anchorClasses);
    private String buildTargetSection(String targetVar, List<String> properties);

    // ── 远程查询 ──
    private List<Map<String, String>> executeRemoteQuery(String sparql, List<String> resultVars);
    private List<Map<String, String>> parseSparqlJsonResults(String json, List<String> resultVars);
    private int findMatchingBracket(String str, int openPos);

    // ── 工具方法 ──
    private String getSimpleName(Resource res);
    private String getPropertyPrefix(Resource prop);

    // ── 内部类 ──
    private record NodePair(Resource prop, Resource domainClass) {}
}
```

### GraphQueryRequest 字段

```java
public class GraphQueryRequest {
    private String queryType;          // path | traverse | pattern
    private SourceTarget target;        // target 节点定义
    private SourceTarget source;        // source 节点定义
    private String mode;               // shortest | all (path)
    private String[] edgeLabels;
    private Integer maxHops;
    private String direction;           // out | in | both (traverse)
    private String resultScope;        // nodes | paths (traverse)
    private PathElement[] path;         // pattern (deprecated, use vertices + edges)
    private PatternVertex[] vertices;   // pattern vertices
    private PatternEdge[] edges;       // pattern edges
    private String where;              // SPARQL FILTER 表达式
    private String[] select;           // 返回的变量列表
    private Boolean dryRun;            // 仅返回生成的 SPARQL，不执行

    public static class SourceTarget {
        private String type;            // e.g., "Organization"
        private String id;              // 可选的实例 URI
        private Map<String, Object> filters;  // e.g., {"name": "研发部"}
    }
}
```

### GraphQueryResponse 结构

```java
public class GraphQueryResponse {
    private String queryType;
    private List<PathResult> paths;
    private Integer totalPaths;
    private List<NodeResult> nodes;
    private Map<String, List<String>> byHop;  // traverse 结果按跳数分组
    private Integer totalResults;

    public static class PathResult {
        private List<NodeResult> nodes;
        private List<EdgeResult> edges;
        private int totalHops;
    }

    public static class NodeResult extends LinkedHashMap<String, Object> {
        // id, type, properties...
    }

    public static class EdgeResult extends LinkedHashMap<String, Object> {
        // id, from, to, label, properties...
    }
}
```