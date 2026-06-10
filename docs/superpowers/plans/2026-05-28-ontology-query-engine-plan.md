# 本体路径查询引擎 - 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `OntologyQueryEngine` Java 类，支持基于本体定义的路径锚点查询，生成 SPARQL 并执行返回结果。

**Architecture:** 使用 Apache Jena ARQ 反向 BFS 遍历本体 RDFS 结构，构建路径子图，生成带 PREFIX + VALUES 的 SPARQL 查询语句。核心流程：加载本体 → 路径搜索 → SPARQL 生成 → 执行解析。

**Tech Stack:** Java 17+ / Apache Jena 6.1.0 / Maven

---

## 文件结构

```
ontology-query-engine/
  pom.xml
  src/main/java/com/jhk/query/
    OntologyQueryEngine.java    # 主类
    ParentNode.java             # 父节点记录
    GraphNode.java             # 路径节点记录
    PathGraph.java            # 路径子图
  src/test/java/com/jhk/query/
    OntologyQueryEngineTest.java  # 单元测试
  src/test/resources/
    my.ttl                    # 测试用本体文件副本
```

---

## Task 1: Maven 项目初始化

**Files:**
- Create: `ontology-query-engine/pom.xml`
- Create: `ontology-query-engine/src/test/resources/my.ttl` (复制 `G:\source\test\my.ttl`)

- [ ] **Step 1: 创建 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.jhk</groupId>
    <artifactId>ontology-query-engine</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <jena.version>6.1.0</jena.version>
        <junit.version>5.10.0</junit.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.apache.jena</groupId>
            <artifactId>jena-arq</artifactId>
            <version>${jena.version}</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-api</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-engine</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.0</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 复制测试本体文件**

将 `G:\source\test\my.ttl` 复制到 `ontology-query-engine/src/test/resources/my.ttl`

---

## Task 2: 内部数据类实现

**Files:**
- Create: `ontology-query-engine/src/main/java/com/jhk/query/ParentNode.java`
- Create: `ontology-query-engine/src/main/java/com/jhk/query/GraphNode.java`
- Create: `ontology-query-engine/src/main/java/com/jhk/query/PathGraph.java`

- [ ] **Step 1: 编写 ParentNode record**

```java
package com.jhk.query;

import org.apache.jena.rdf.model.Resource;

/**
 * 记录路径中指向当前类的父节点
 * @param parentClass 父节点类 Resource
 * @param property    链接父子类的属性 Resource
 */
public record ParentNode(Resource parentClass, Resource property) {}
```

- [ ] **Step 2: 编写 GraphNode record**

```java
package com.jhk.query;

import org.apache.jena.rdf.model.Resource;
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
}
```

- [ ] **Step 3: 编写 PathGraph 类**

```java
package com.jhk.query;

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
}
```

- [ ] **Step 4: 提交**

```bash
git add ParentNode.java GraphNode.java PathGraph.java
git commit -m "feat: add internal data classes"
```

---

## Task 3: OntologyQueryEngine 构造器

**Files:**
- Create: `ontology-query-engine/src/main/java/com/jhk/query/OntologyQueryEngine.java`

- [ ] **Step 1: 编写测试**

```java
@Test
void testConstructor_loadValidTTL() {
    String path = "src/test/resources/my.ttl";
    OntologyQueryEngine engine = new OntologyQueryEngine(path);
    assertNotNull(engine);
}

@Test
void testConstructor_fileNotFound() {
    assertThrows(IOException.class, () -> new OntologyQueryEngine("nonexistent.ttl"));
}
```

- [ ] **Step 2: 运行测试验证失败（文件未创建）**

- [ ] **Step 3: 编写最小实现**

```java
package com.jhk.query;

import org.apache.jena.rdf.model.*;
import org.apache.jena.reasoner.*;
import org.apache.jena.util.*;
import java.io.*;
import java.nio.file.*;

public class OntologyQueryEngine {

    private static final String NS_CLASS = "http://www.jhk.com/finance/business-analysis/class/";
    private static final String NS_PROP  = "http://www.jhk.com/finance/business-analysis/property/";
    private static final String PREFIX_BACLS  = "bacls";
    private static final String PREFIX_BAPROP = "baprop";
    private static final int DEFAULT_MAX_DEPTH = 6;
    private static final int DEFAULT_MAX_PATHS = 10;

    private final Model model;

    public OntologyQueryEngine(String ontologyPath) throws IOException {
        Model rawModel = FileManager.getInternal().loadModel(ontologyPath);
        if (rawModel.isEmpty()) {
            throw new IOException("Failed to load ontology: " + ontologyPath);
        }
        InfModel infModel = ModelFactory.createInfModel(
            ReasonerRegistry.getRDFSReasoner(), rawModel);
        this.model = infModel;
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

- [ ] **Step 5: 提交**

---

## Task 4: classUri / propUri / varName 辅助方法

**Files:**
- Modify: `OntologyQueryEngine.java` (添加内部方法)

- [ ] **Step 1: 编写测试**

```java
@Test
void testClassUri() {
    OntologyQueryEngine engine = new OntologyQueryEngine(path);
    Resource org = engine.classUri("Organization");
    assertEquals(NS_CLASS + "Organization", org.getURI());
}

@Test
void testVarName() {
    assertEquals("?org", engine.varName("Organization"));
    assertEquals("?scenario", engine.varName("AnalysisScenario"));
    assertEquals("?target", engine.varName("PainPoint"));
    assertEquals("?mdu", engine.varName("MetricDimensionUnit"));
}
```

- [ ] **Step 2: 添加实现**

```java
private Resource classUri(String simpleName) {
    return model.createResource(NS_CLASS + simpleName);
}

private Resource propUri(String simpleName) {
    return model.createResource(NS_PROP + simpleName);
}

private String varName(String className) {
    Map<String, String> ALIAS = Map.of(
        "MetricDimensionUnit", "mdu",
        "AbnormalRule", "rule",
        "PainPoint", "target"
    );
    if (ALIAS.containsKey(className)) return "?" + ALIAS.get(className);
    return "?" + Character.toLowerCase(className.charAt(0)) + className.substring(1);
}
```

- [ ] **Step 3: 运行测试验证**

- [ ] **Step 4: 提交**

---

## Task 5: reverseBfs 路径搜索

**Files:**
- Modify: `OntologyQueryEngine.java` (添加 reverseBfs 及 getIncomingEdges)

- [ ] **Step 1: 编写测试**

```java
@Test
void testReverseBfs_reachesOrganization() {
    OntologyQueryEngine engine = new OntologyQueryEngine(path);
    Resource painPoint = engine.classUri("PainPoint");
    Map<Resource, ParentNode> parentMap = engine.reverseBfs(painPoint, 6);
    Resource org = engine.classUri("Organization");
    assertTrue(parentMap.containsKey(org), "Organization should be reachable from PainPoint");
}
```

- [ ] **Step 2: 添加 getIncomingEdges 实现**

```java
/**
 * 查找所有对象属性，其 range == currentClass
 * 返回 [(property, domainClass), ...]
 */
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

- [ ] **Step 3: 添加 reverseBfs 实现**

```java
Map<Resource, ParentNode> reverseBfs(Resource targetClass, int maxDepth) {
    Map<Resource, ParentNode> parentMap = new HashMap<>();
    Set<Resource> visited = new HashSet<>();
    Deque<Resource> queue = new ArrayDeque<>();
    queue.add(targetClass);
    visited.add(targetClass);

    while (!queue.isEmpty()) {
        Resource current = queue.poll();
        int depth = parentMap.getOrDefault(current, new ParentNode(null, null)).depth();

        for (NodePair edge : getIncomingEdges(current)) {
            Resource parentClass = edge.domainClass();
            if (!visited.contains(parentClass)) {
                parentMap.put(parentClass, new ParentNode(current, edge.prop(), depth + 1));
                visited.add(parentClass);
                queue.add(parentClass);
            }
        }
    }
    return parentMap;
}
```

注意：`NodePair` 和 `ParentNode` 需要扩展 depth 字段，或用单独类存储。先用简化版实现。

- [ ] **Step 4: 运行测试验证**

- [ ] **Step 5: 提交**

---

## Task 6: buildPathGraph 路径子图合并

**Files:**
- Modify: `OntologyQueryEngine.java` (添加 buildPathGraph)

- [ ] **Step 1: 编写测试**

```java
@Test
void testBuildPathGraph_mergesSharedNodes() {
    // 测试多锚点在同一中间节点汇合时，变量共享
    OntologyQueryEngine engine = new OntologyQueryEngine(path);
    // 手动构建 parentMap 并调用 buildPathGraph
}
```

- [ ] **Step 2: 实现 buildPathGraph**

从 parentMap 从目标向锚点回溯，构建主路径树。共享子路径只出现一次。

- [ ] **Step 3: 运行测试验证**

- [ ] **Step 4: 提交**

---

## Task 7: SPARQL 生成（buildQuery 核心）

**Files:**
- Modify: `OntologyQueryEngine.java` (添加 SPARQL 相关方法)

- [ ] **Step 1: 编写测试**

```java
@Test
void testBuildQuery_returnsSparqlString() {
    OntologyQueryEngine engine = new OntologyQueryEngine(path);
    Map<String, List<String>> constraints = Map.of(
        "Organization", List.of("http://.../冰冷事业部")
    );
    String sparql = engine.buildQuery(constraints, "PainPoint", List.of());
    assertTrue(sparql.contains("PREFIX bacls:"));
    assertTrue(sparql.contains("baprop:involvesScenario"));
    assertTrue(sparql.contains("VALUES ?org"));
}
```

- [ ] **Step 2: 实现 buildPrefixSection**

```java
private String buildPrefixSection() {
    return """
        PREFIX bacls: <http://www.jhk.com/finance/business-analysis/class/>
        PREFIX baprop: <http://www.jhk.com/finance/business-analysis/property/>
        """;
}
```

- [ ] **Step 3: 实现 buildValuesSection**

```java
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
```

- [ ] **Step 4: 实现 buildPathSection**

基于 PathGraph 遍历，生成 `?var prop ?nextVar` 三元组。

- [ ] **Step 5: 实现 buildQuery**

组合所有 Section，返回完整 SPARQL 字符串。

- [ ] **Step 6: 运行测试验证 SPARQL 语法正确**

- [ ] **Step 7: 提交**

---

## Task 8: query 执行 + 结果解析

**Files:**
- Modify: `OntologyQueryEngine.java` (添加 query 方法和 parseResults)

- [ ] **Step 1: 编写测试**

```java
@Test
void testQuery_executesAndReturnsResults() {
    OntologyQueryEngine engine = new OntologyQueryEngine(path);
    Map<String, List<String>> constraints = Map.of(
        "Organization", List.of("http://.../冰冷事业部")
    );
    List<Map<String, String>> results = engine.query(constraints, "PainPoint", List.of("painPointPainPointDesc"));
    assertNotNull(results);
}
```

- [ ] **Step 2: 实现 query 方法**

```java
public List<Map<String, String>> query(
    Map<String, List<String>> constraints,
    String targetClass,
    List<String> targetProperties
) {
    String sparql = buildQuery(constraints, targetClass, targetProperties);
    Query query = QueryFactory.create(sparql);
    try (QueryExecution qe = QueryExecutionFactory.create(query, model)) {
        ResultSet rs = qe.execSelect();
        return parseResults(rs, "?target", targetProperties);
    }
}
```

- [ ] **Step 3: 实现 parseResults**

将 ResultSet 转换为 `List<Map<String, String>>`。

- [ ] **Step 4: 运行测试**

- [ ] **Step 5: 提交**

---

## Task 9: 端到端集成测试

- [ ] **Step 1: 编写完整集成测试**

```java
@Test
void testEndToEnd_withMultipleAnchors() {
    OntologyQueryEngine engine = new OntologyQueryEngine(path);
    Map<String, List<String>> constraints = Map.of(
        "Organization", List.of("http://.../冰冷事业部"),
        "AnalysisScenario", List.of("http://.../营业收入"),
        "Dimension", List.of("http://.../内销")
    );
    String sparql = engine.buildQuery(constraints, "PainPoint", List.of("painPointPainPointDesc"));
    System.out.println(sparql);
    assertTrue(sparql.contains("VALUES ?org"));
    assertTrue(sparql.contains("VALUES ?scenario"));
    assertTrue(sparql.contains("VALUES ?dim"));
}
```

- [ ] **Step 2: 运行所有测试**

- [ ] **Step 3: 提交完成**

---

## 执行选项

**1. Subagent-Driven (recommended)** - 每 Task 由独立 subagent 执行，Task 间审查
**2. Inline Execution** - 在当前 session 执行，batch 带 checkpoint

选择哪种方式？