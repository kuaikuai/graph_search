# 统一图搜索API实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 根据 2026-06-04-unified-graph-search-api-design.md 设计文档，补全现有实现中的缺失功能

**Architecture:** 基于 Javalin + Jena SPARQL 的 REST API，通过 GraphBackend 接口支持多后端（当前仅 SPARQL）

**Tech Stack:** Java 17, Javalin 5.6.3, Apache Jena 6.1.0, Jackson, SnakeYAML

---

## 现状分析

### 已实现 ✅
- API端点: `POST /graph/{reponame}/query`
- 请求结构: source/target/path/where/select/dryRun
- 三种查询类型: path / traverse / pattern
- 响应结构: success/data/error 统一格式
- filters 直接值匹配

### 缺失 ❌
1. **filters 操作符**: `$gt`, `$gte`, `$lt`, `$lte`, `$in`, `$nin`, `$contains`, `$startsWith`, `$endsWith`, `$between`
2. **path mode**: 设计文档支持 `shortest`(默认)/`all`，当前只返回单一路径
3. **where 子句验证**: pattern 查询的 where 直接拼接，未验证
4. **错误码处理**: VERTEX_NOT_FOUND / EDGE_NOT_FOUND 未明确抛出
5. **节点属性**: path 响应中节点属性不完整

---

## 文件影响范围

| 文件 | 职责 |
|------|------|
| `src/main/java/com/jhk/graph/dto/request/GraphQueryRequest.java` | 添加 filters 操作符解析逻辑 |
| `src/main/java/com/jhk/graph/backend/sparql/SparqlBackend.java` | 实现 path mode=all、filter 操作符转换、错误码处理 |
| `src/main/java/com/jhk/graph/query/OntologyQueryEngine.java` | 支持 filter 操作符的 SPARQL 生成 |
| `src/test/java/com/jhk/graph/backend/sparql/SparqlBackendTest.java` | 补充测试用例 |

---

## 任务分解

### Task 1: 实现 filters 操作符解析与 SPARQL 转换

**目标:** 支持设计文档中定义的所有 filter 操作符

**Files:**
- Modify: `src/main/java/com/jhk/graph/dto/request/GraphQueryRequest.java`
- Modify: `src/main/java/com/jhk/graph/backend/sparql/SparqlBackend.java:buildPathQueryWithInlineSourceFilter`
- Modify: `src/main/java/com/jhk/graph/query/OntologyQueryEngine.java`

- [ ] **Step 1: 在 SourceTarget 内部类中添加 filters 操作符解析方法**

在 `GraphQueryRequest.SourceTarget` 中添加 `Map<String, Object> resolveFilters(Map<String, Object> rawFilters)` 方法，将操作符格式转换为 SPARQL 条件。

```java
// filters 操作符映射示例
// "age": { "$gt": 30 }  →  FILTER(?age > 30)
// "name": { "$contains": "张" }  →  FILTER(CONTAINS(?name, "张"))
// "city": { "$in": ["北京", "上海"] }  →  FILTER(?city IN ("北京", "上海"))
```

- [ ] **Step 2: 修改 SparqlBackend 中的 SPARQL 生成逻辑以支持操作符**

在 `buildPathQueryWithInlineSourceFilter` 和 `buildPatternSparql` 中使用新的操作符解析逻辑。

- [ ] **Step 3: 添加单元测试验证操作符功能**

在 `SparqlBackendTest.java` 中添加测试用例。

---

### Task 2: 实现 path mode=all 支持

**目标:** 当 mode="all" 时，返回所有可达路径而非单一路径

**Files:**
- Modify: `src/main/java/com/jhk/graph/backend/sparql/SparqlBackend.java:executePathQuery`

- [ ] **Step 1: 修改 executePathQuery 以支持 mode=all**

```java
// 在 executePathQuery 中添加逻辑
String mode = request.getMode() != null ? request.getMode() : "shortest";
if ("all".equals(mode)) {
    // 使用 BFS/DFS 查找所有路径
} else {
    // 当前实现：返回单一最短路径
}
```

- [ ] **Step 2: 添加测试用例验证 mode=all**

---

### Task 3: 添加 where 子句验证

**目标:** pattern 查询的 where 子句进行基本验证，防止无效 SPARQL

**Files:**
- Modify: `src/main/java/com/jhk/graph/backend/sparql/SparqlBackend.java:buildPatternSparql`

- [ ] **Step 1: 添加 where 子句基本验证**

检查 where 子句中引用的变量是否在 path 的 select 中声明。

- [ ] **Step 2: 添加测试用例**

---

### Task 4: 添加 VERTEX_NOT_FOUND / EDGE_NOT_FOUND 错误处理

**目标:** 当起点或终点节点不存在时抛出明确的错误码

**Files:**
- Modify: `src/main/java/com/jhk/graph/backend/sparql/SparqlBackend.java`
- Modify: `src/main/java/com/jhk/graph/GraphSearchApplication.java:mapCodeToStatus`

- [ ] **Step 1: 在 SparqlBackend 中添加节点不存在检查**

在 executePathQuery、executeTraverseQuery 开始时验证 source/target 是否存在。

- [ ] **Step 2: 确保 HTTP 状态码映射正确**

`mapCodeToStatus` 中 VERTEX_NOT_FOUND → 404, EDGE_NOT_FOUND → 404 已实现。

---

### Task 5: 增强响应节点属性

**目标:** path 响应中的节点应包含完整属性（不仅是 id 和 type）

**Files:**
- Modify: `src/main/java/com/jhk/graph/backend/sparql/SparqlBackend.java:executePathQuery`

- [ ] **Step 1: 为 path 响应中的节点填充属性**

当前实现中节点 properties 为空 Map，需要从 SPARQL 结果中提取属性。

---

### Task 6: 验证实现

**Files:**
- Test: `src/test/java/com/jhk/graph/backend/sparql/SparqlBackendTest.java`
- Run: `mvn test`

- [ ] **Step 1: 运行现有测试确保无回归**
- [ ] **Step 2: 运行新增测试验证功能**
- [ ] **Step 3: 构建并验证**

---

## 执行选项

**"Plan complete. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?"**
