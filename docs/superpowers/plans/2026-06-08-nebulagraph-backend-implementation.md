# NebulaGraph 后端支持实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 NebulaGraph 图数据库后端支持，通过 GraphBackend 接口将统一 JSON 请求转换为 nGQL 查询

**Architecture:** 创建 NebulaBackend、NebulaConnection、NgqlConverter 三个组件，遵循现有 GraphBackend 接口模式

**Tech Stack:** Java 17, NebulaGraph Java Client 3.8.0, Javalin 5.6.3

---

## 文件结构

| 组件 | 文件路径 | 职责 |
|------|----------|------|
| NebulaConnection | `src/main/java/com/jhk/graph/backend/nebula/NebulaConnection.java` | 连接管理、会话池、认证 |
| NgqlConverter | `src/main/java/com/jhk/graph/backend/nebula/NgqlConverter.java` | JSON → nGQL 查询转换 |
| NebulaBackend | `src/main/java/com/jhk/graph/backend/nebula/NebulaBackend.java` | 实现 GraphBackend 接口 |
| 修改 | `src/main/java/com/jhk/graph/config/BackendConfig.java` | 添加配置解析 |
| 修改 | `src/main/java/com/jhk/graph/GraphSearchApplication.java` | 注册 NebulaBackend 工厂 |

---

## Task 1: 添加 NebulaGraph Java Client 依赖

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: 添加 nebula-java 依赖**

在 `<dependencies>` 中添加:
```xml
<!-- NebulaGraph Java Client -->
<dependency>
    <groupId>com.vesoft</groupId>
    <artifactId>client</artifactId>
    <version>3.8.0</version>
</dependency>
```

- [ ] **Step 2: 验证依赖下载**

Run: `mvn dependency:resolve -q`
Expected: 无错误

---

## Task 2: 创建 NebulaConnection 组件

**Files:**
- Create: `src/main/java/com/jhk/graph/backend/nebula/NebulaConnection.java`

- [ ] **Step 1: 创建 NebulaConnection 类框架**

```java
package com.jhk.graph.backend.nebula;

import com.vesoft.nebula.client.graph.NebulaPool;
import com.vesoft.nebula.client.graph.Session;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.jhk.graph.config.BackendConfig.NebulaProperties;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class NebulaConnection {
    private final NebulaPool pool;
    private final String space;
    private final ConcurrentHashMap<Long, Session> sessions;
    private final int timeout;

    public NebulaConnection(NebulaProperties config) {
        // 初始化连接池
    }

    public Session getSession() {
        // 获取会话
    }

    public void releaseSession(Session session) {
        // 释放会话回池
    }

    public void close() {
        // 关闭连接池
    }
}
```

- [ ] **Step 2: 实现连接池初始化**

在构造函数中:
```java
List<HostAddress> addresses = Arrays.stream(config.getAddress().split(","))
    .map(addr -> new HostAddress(addr.trim().split(":")[0],
                                 Integer.parseInt(addr.trim().split(":")[1])))
    .collect(Collectors.toList());

NebulaPoolConfig poolConfig = new NebulaPoolConfig();
poolConfig.setMaxConnSize(config.getPoolSize());
poolConfig.setTimeout(config.getTimeout());

this.pool = new NebulaPool();
this.pool.init(addresses, poolConfig, config.getUsername(), config.getPassword());
```

- [ ] **Step 3: 实现 getSession/releaseSession**

```java
public Session getSession() {
    return pool.getSession();
}

public void releaseSession(Session session) {
    session.release();
}
```

- [ ] **Step 4: 验证编译**

Run: `mvn compile -q`
Expected: 无错误

---

## Task 3: 创建 NgqlConverter 组件

**Files:**
- Create: `src/main/java/com/jhk/graph/backend/nebula/NgqlConverter.java`

- [ ] **Step 1: 创建 NgqlConverter 类框架**

```java
package com.jhk.graph.backend.nebula;

import com.jhk.graph.dto.request.GraphQueryRequest;
import com.jhk.graph.dto.request.PathElement;
import java.util.Map;

public class NgqlConverter {

    public String toNgql(GraphQueryRequest request) {
        return switch (request.getQueryType()) {
            case "path" -> convertPath(request);
            case "traverse" -> convertTraverse(request);
            case "pattern" -> convertPattern(request);
            default -> throw new IllegalArgumentException("Unsupported queryType: " + request.getQueryType());
        };
    }
}
```

- [ ] **Step 2: 实现 filter 条件转换**

```java
private String convertFilter(String varName, Object filter) {
    // 直接值: name == "张三"
    // $eq: name == "X"
    // $gt: age > 30
    // $in: name IN ["A", "B"]
    // $contains: CONTAINS(name, "X")
    // ...
}
```

- [ ] **Step 3: 实现 path 查询转换**

```java
private String convertPath(GraphQueryRequest request) {
    // 1. LOOKUP 找到 source VID
    // 2. GO 多跳到 target
    // 3. YIELD 结果
}
```

- [ ] **Step 4: 实现 traverse 查询转换**

```java
private String convertTraverse(GraphQueryRequest request) {
    // GO N STEPS FROM source OVER *
}
```

- [ ] **Step 5: 实现 pattern 查询转换**

```java
private String convertPattern(GraphQueryRequest request) {
    // MATCH (a)-[e1]->(b)-[e2]->(c)
    // WHERE ...
    // RETURN ...
}
```

- [ ] **Step 6: 验证编译**

Run: `mvn compile -q`
Expected: 无错误

---

## Task 4: 创建 NebulaBackend 组件

**Files:**
- Create: `src/main/java/com/jhk/graph/backend/nebula/NebulaBackend.java`

- [ ] **Step 1: 创建 NebulaBackend 类框架**

```java
package com.jhk.graph.backend.nebula;

import com.jhk.graph.backend.GraphBackend;
import com.jhk.graph.config.BackendConfig.NebulaProperties;
import com.jhk.graph.dto.request.GraphQueryRequest;
import com.jhk.graph.dto.response.GraphQueryResponse;
import com.jhk.graph.exception.GraphQueryException;

public class NebulaBackend implements GraphBackend {
    private final NebulaConnection connection;
    private final NgqlConverter converter;

    public NebulaBackend(NebulaProperties config) {
        this.connection = new NebulaConnection(config);
        this.converter = new NgqlConverter();
    }

    @Override
    public GraphQueryResponse execute(GraphQueryRequest request) {
        String ngql = converter.toNgql(request);
        return executeNgql(ngql, request.getQueryType());
    }

    @Override
    public String getType() {
        return "nebula";
    }

    @Override
    public String buildSparql(GraphQueryRequest request) {
        return converter.toNgql(request);
    }
}
```

- [ ] **Step 2: 实现 executeNgql 方法**

```java
private GraphQueryResponse executeNgql(String ngql, String queryType) {
    Session session = connection.getSession();
    try {
        ResultSet rs = session.execute(ngql);
        if (!rs.isSucceeded()) {
            throw new GraphQueryException("BACKEND_ERROR", rs.getErrorMessage());
        }
        return parseResult(rs, queryType);
    } finally {
        connection.releaseSession(session);
    }
}
```

- [ ] **Step 3: 实现结果解析**

```java
private GraphQueryResponse parseResult(ResultSet rs, String queryType) {
    // 解析 ResultSet 为 GraphQueryResponse
}
```

- [ ] **Step 4: 验证编译**

Run: `mvn compile -q`
Expected: 无错误

---

## Task 5: 修改 BackendConfig 配置解析

**Files:**
- Modify: `src/main/java/com/jhk/graph/config/BackendConfig.java`

- [ ] **Step 1: 确保 NebulaProperties 配置完整**

当前 NebulaProperties 已存在，确认字段完整:
- `address`, `username`, `password`, `space`, `poolSize`, `timeout`

- [ ] **Step 2: 验证编译**

Run: `mvn compile -q`
Expected: 无错误

---

## Task 6: 修改 GraphSearchApplication 注册 NebulaBackend

**Files:**
- Modify: `src/main/java/com/jhk/graph/GraphSearchApplication.java`

- [ ] **Step 1: 添加 NebulaBackend 初始化逻辑**

在 `getBackend` 方法中添加:
```java
} else if ("nebula".equals(backendType)) {
    Map<String, Object> nebulaCfg = (Map<String, Object>) repoConfig.get("nebula");
    NebulaProperties props = new NebulaProperties();
    props.setAddress((String) nebulaCfg.get("address"));
    props.setUsername((String) nebulaCfg.getOrDefault("username", "root"));
    props.setPassword((String) nebulaCfg.getOrDefault("password", ""));
    props.setSpace((String) nebulaCfg.get("space"));
    Object poolSize = nebulaCfg.get("poolSize");
    props.setPoolSize(poolSize instanceof Number ? ((Number) poolSize).intValue() : 5);
    Object timeout = nebulaCfg.get("timeout");
    props.setTimeout(timeout instanceof Number ? ((Number) timeout).intValue() : 3000);

    backend = new NebulaBackend(props);
}
```

- [ ] **Step 2: 添加 import**

```java
import com.jhk.graph.backend.nebula.NebulaBackend;
import com.jhk.graph.config.BackendConfig.NebulaProperties;
```

- [ ] **Step 3: 验证编译**

Run: `mvn compile -q`
Expected: 无错误

---

## Task 7: 添加单元测试

**Files:**
- Create: `src/test/java/com/jhk/graph/backend/nebula/NgqlConverterTest.java`
- Create: `src/test/java/com/jhk/graph/backend/nebula/NebulaBackendTest.java`

- [ ] **Step 1: 测试 NgqlConverter.path 查询转换**

```java
@Test
void testConvertPathQuery() {
    GraphQueryRequest request = new GraphQueryRequest();
    request.setQueryType("path");
    request.setSource(...);
    request.setTarget(...);

    String ngql = converter.toNgql(request);
    assertTrue(ngql.contains("LOOKUP"));
    assertTrue(ngql.contains("GO"));
}
```

- [ ] **Step 2: 测试 NgqlConverter.pattern 查询转换**

```java
@Test
void testConvertPatternQuery() {
    // 测试 MATCH 语法
}
```

- [ ] **Step 3: 运行测试**

Run: `mvn test -Dtest=NgqlConverterTest -q`
Expected: PASS

---

## Task 8: 构建并验证

- [ ] **Step 1: 完整编译**

Run: `mvn compile -q`
Expected: 无错误

- [ ] **Step 2: 运行所有测试**

Run: `mvn test -q`
Expected: 无失败

- [ ] **Step 3: 打包**

Run: `mvn package -DskipTests -q`
Expected: 生成 jar 文件

---

## 执行选项

**"Plan complete and saved to `docs/superpowers/plans/2026-06-08-nebulagraph-backend-implementation.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?"**