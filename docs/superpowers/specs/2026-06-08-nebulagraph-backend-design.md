# NebulaGraph 后端支持设计

## 概述

为统一图搜索 API 添加 NebulaGraph 图数据库后端支持，通过 `GraphBackend` 接口实现统一 JSON 请求到 nGQL 查询的转换。

## 架构

```
统一 JSON 请求
    ↓
NebulaBackend (实现 GraphBackend 接口)
    ↓
JSON → nGQL 转换 (NgqlConverter)
    ↓
NebulaGraph Java Client (nebula-java 3.8.0)
    ↓
返回统一 GraphQueryResponse
```

## 组件

| 组件 | 包路径 | 职责 |
|------|--------|------|
| NebulaBackend | `com.jhk.graph.backend.nebula.NebulaBackend` | 实现 GraphBackend 接口，核心转换逻辑 |
| NebulaConnection | `com.jhk.graph.backend.nebula.NebulaConnection` | 连接管理、会话池、认证 |
| NgqlConverter | `com.jhk.graph.backend.nebula.NgqlConverter` | JSON → nGQL 查询语句转换 |

## 配置

### config.yaml 结构

```yaml
repositories:
  nebula-repo:
    type: nebula
    nebula:
      address: "192.168.1.100:9669,192.168.1.101:9669"  # 集群地址，逗号分隔
      username: "root"
      password: "nebula123"
      space: "test_space"
      poolSize: 10        # 连接池大小，默认5
      timeout: 5000       # 查询超时(ms)，默认3000
```

### NebulaProperties 配置类

扩展 `BackendConfig.NebulaProperties`:
- `address`: Graph 服务地址（集群用逗号分隔）
- `username`: 用户名
- `password`: 密码
- `space`: 图空间名
- `poolSize`: 连接池大小
- `timeout`: 超时时间(ms)

## 查询转换

### Path 查询 → nGQL

**输入:**
```json
{
  "queryType": "path",
  "source": { "type": "Organization", "filters": { "name": "冰冷事业部" } },
  "target": { "type": "PainPoint" },
  "mode": "shortest",
  "maxHops": 5
}
```

**转换逻辑:**
1. 先用 `LOOKUP` 找到匹配 source filters 的起始点 VID
2. 使用 `GO` 多跳遍历到 target 类型的节点
3. 限制 maxHops 跳数，使用 `LIMIT 1` 取最短路径

**输出 nGQL (示例):**
```ngql
LOOKUP ON Organization WHERE Organization.name == "冰冷事业部" YIELD id(vertex) AS source_id |
GO 5 STEPS FROM $-.source_id OVER * WHERE dst() == "PainPoint"
YIELD src(edge) AS src, dst(edge) AS dst | LIMIT 1
```

### Traverse 查询 → nGQL

**输入:**
```json
{
  "queryType": "traverse",
  "source": { "type": "Organization", "filters": { "name": "冰冷事业部" } },
  "direction": "out",
  "maxHops": 3,
  "resultScope": "nodes"
}
```

**输出 nGQL (示例):**
```ngql
LOOKUP ON Organization WHERE Organization.name == "冰冷事业部" YIELD id(vertex) AS source_id |
GO 3 STEPS FROM $-.source_id OVER *
YIELD dst(edge) AS node_id | LIMIT -1
```

### Pattern 查询 → nGQL

**输入:**
```json
{
  "queryType": "pattern",
  "path": [
    { "type": "Person", "filters": { "name": "张三" }, "as": "person" },
    { "edge": "worksAt", "as": "rel1" },
    { "type": "Company", "as": "company" },
    { "edge": "locatedIn", "as": "rel2" },
    { "type": "City", "filters": { "name": "北京" }, "as": "city" }
  ],
  "select": ["person", "company", "city"]
}
```

**输出 nGQL:**
```ngql
MATCH (person:Person)-[rel1:worksAt]->(company:Company)-[rel2:locatedIn]->(city:City)
WHERE person.name == "张三" AND city.name == "北京"
RETURN person, company, city
```

## Filter 操作符映射

| JSON 操作符 | nGQL |
|-------------|------|
| 直接值 `"name": "张三"` | `name == "张三"` |
| `$eq` | `==` |
| `$ne` | `!=` |
| `$gt` | `>` |
| `$gte` | `>=` |
| `$lt` | `<` |
| `$lte` | `<=` |
| `$in` | `IN [ ]` |
| `$nin` | `NOT IN [ ]` |
| `$contains` | `CONTAINS` |
| `$startsWith` | `STARTS WITH` |
| `$endsWith` | `ENDS WITH` |
| `$between` | `>= AND <=` |

## 错误处理

| 错误码 | HTTP状态 | 触发条件 |
|--------|----------|----------|
| VERTEX_NOT_FOUND | 404 | source/target 节点不存在 |
| EDGE_NOT_FOUND | 404 | 指定边不存在 |
| TIMEOUT | 408 | 查询超时 |
| BACKEND_ERROR | 500 | NebulaGraph 连接/执行错误 |
| INVALID_PATTERN | 400 | nGQL 语法错误 |
| INVALID_FILTER | 400 | filter 转换失败 |

## 依赖

```xml
<!-- NebulaGraph Java Client -->
<dependency>
    <groupId>com.vesoft</groupId>
    <artifactId>client</artifactId>
    <version>3.8.0</version>
</dependency>
```

## 实现任务

1. 创建 `NebulaConnection` - 连接管理、会话池
2. 创建 `NgqlConverter` - 查询转换逻辑
3. 创建 `NebulaBackend` - 实现 GraphBackend 接口
4. 修改 `BackendConfig` - 添加配置解析
5. 修改 `GraphSearchApplication` - 注册 NebulaBackend 工厂
6. 添加单元测试

## 注意事项

1. NebulaGraph VID 类型为字符串，无需类型转换
2. 集群环境下 address 支持逗号分隔多个地址
3. 连接池需支持多线程并发查询
4. 会话超时需定期刷新保持活跃