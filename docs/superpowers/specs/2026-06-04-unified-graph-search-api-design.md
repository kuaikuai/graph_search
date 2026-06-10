# 统一图搜索API设计

## 概述

提供统一的JSON HTTP API对外暴露图搜索服务，后端实际可能是SPARQL端点或NebulaGraph。通过统一的输入输出格式屏蔽后端差异。

## 技术栈

- Java/Javalin (非Spring Boot)
- REST API，POST /graph/{reponame}/query
- 配置文件 (config.yaml) 管理后端连接
- 无认证（内部网络）

## API 端点

### POST /graph/{reponame}/query

Content-Type: application/json

## 请求格式

### 通用字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| queryType | string | 是 | 查询类型：path / traverse / pattern |
| dryRun | boolean/string | 否 | true时仅返回生成的查询语句，不执行。默认false。支持布尔值或字符串"true"/"false" |
| limit | int | 否 | 结果条数限制，默认10 |

### 1. 路径查询 (path)

根据起点和终点，查找路径。

**输入**:
```json
{
  "queryType": "path",
  "source": {
    "type": "Person",
    "filters": { "name": "张三" }
  },
  "target": {
    "type": "Company",
    "filters": { "industry": "IT" }
  },
  "mode": "shortest",
  "edgeLabels": ["worksAt", "owns"],
  "minHops": 1,
  "maxHops": 5
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| queryType | string | 是 | 固定值 path |
| source | object | 是 | 起点节点定义（type + filters） |
| target | object | 否 | 终点节点定义（type + filters） |
| mode | string | 否 | shortest(默认) / all |
| edgeLabels | string[] | 否 | 边的类型白名单 |
| minHops | int | 否 | 最小跳数，默认1 |
| maxHops | int | 否 | 最大跳数，默认5 |
| direction | string | 否 | out(默认) / in，路径方向 |
| targetProperties | string[] | 否 | 指定要获取的目标节点属性，如 ["Organization.orgOrgName"] |
| limit | int | 否 | 结果条数限制，默认10 |

### 2. 遍历查询 (traverse)

从起点出发，按指定方向和跳数遍历图，返回完整路径。

**输入**:
```json
{
  "queryType": "traverse",
  "source": {
    "type": "Person",
    "filters": { "name": "张三" }
  },
  "direction": "out",
  "edgeLabels": ["worksAt"],
  "minHops": 1,
  "maxHops": 3,
  "resultScope": "paths"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| queryType | string | 是 | 固定值 traverse |
| source | object | 是 | 起始节点定义 |
| direction | string | 否 | out(默认) / in / both |
| edgeLabels | string[] | 否 | 边的类型白名单 |
| minHops | int | 否 | 最小跳数，默认1 |
| maxHops | int | 否 | 最大跳数，默认3 |
| resultScope | string | 否 | nodes / paths(默认) |
| limit | int | 否 | 结果条数限制，默认10 |

### 3. 模式匹配 (pattern)

通过节点(nodes)和边(edges)定义匹配子图。

**输入**:
```json
{
  "queryType": "pattern",
  "nodes": [
    { "id": "person", "type": "Person", "filters": { "name": "张三" } },
    { "id": "company", "type": "Company", "filters": { "employees": { "$gt": 100 } } },
    { "id": "city", "type": "City", "filters": { "name": "北京" } }
  ],
  "edges": [
    { "from": "person", "to": "company", "label": "worksAt" },
    { "from": "company", "to": "city", "label": "locatedIn" }
  ],
  "select": ["person", "company", "city"]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| queryType | string | 是 | 固定值 pattern |
| nodes | object[] | 是 | 节点定义数组（原vertices） |
| edges | object[] | 是 | 边定义数组 |
| select | string[] | 否 | 返回的节点ID列表，默认返回完整路径p |

**nodes节点格式**:
```json
{
  "id": "person",
  "type": "Person",
  "filters": { "name": "张三", "age": { "$gt": 30 } }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | string | 是 | 节点唯一标识，用于edges引用 |
| type | string | 是 | 节点类型/标签 |
| filters | object | 否 | 属性过滤条件 |

**edges 边格式**:
```json
{
  "from": "person",
  "to": "company",
  "label": "worksAt",
  "direction": "out",
  "minHops": 1,
  "maxHops": 3,
  "filters": { "startYear": { "$gt": 2010 } }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| from | string | 是 | 起点的节点ID |
| to | string | 是 | 终点的节点ID |
| label | string | 否 | 边类型，默认任意边 |
| direction | string | 否 | out(默认) / in / both |
| minHops | int | 否 | 最小跳数，默认1 |
| maxHops | int | 否 | 最大跳数，默认1 |
| filters | object | 否 | 边属性过滤条件 |

**边属性过滤示例**：
```json
{ "from": "person", "to": "company", "label": "worksAt", "filters": { "position": "工程师" } }
```

**多跳路径示例**（查找员工的上司）：
```json
{
  "queryType": "pattern",
  "nodes": [
    { "id": "e1", "type": "Employee", "filters": { "name": "张三" } },
    { "id": "mgr", "type": "Manager" }
  ],
  "edges": [
    { "from": "e1", "to": "mgr", "label": "reportsTo", "minHops": 1, "maxHops": 2 }
  ],
  "select": ["e1", "mgr"]
}
```

### source / target / filters 结构

**source/target** 用于 path 和 traverse 查询:
```json
{
  "type": "Person",
  "id": "optional-id",
  "filters": {
    "name": "张三",
    "age": { "$gt": 30 }
  }
}
```

**filters操作符**:

| 操作符 | 示例 | 说明 |
|--------|------|------|
| 直接值 | "name": "张三" | 等于 (eq) |
| $eq | "name": { "$eq": "张三" } | 等于 |
| $ne | "name": { "$ne": "张三" } | 不等于 |
| $gt | "age": { "$gt": 30 } | 大于 |
| $gte | "age": { "$gte": 30 } | 大于等于 |
| $lt | "age": { "$lt": 30 } | 小于 |
| $lte | "age": { "$lte": 30 } | 小于等于 |
| $in | "city": { "$in": ["北京", "上海"] } | 在列表中 |
| $nin | "city": { "$nin": ["广州"] } | 不在列表中 |
| $contains | "name": { "$contains": "张" } | 包含 |
| $startsWith | "name": { "$startsWith": "张" } | 开头匹配 |
| $endsWith | "name": { "$endsWith": "三" } | 结尾匹配 |
| $between | "age": { "$between": [18, 65] } | 范围 |

## 响应格式

### 统一结构

```json
{
  "success": true,
  "data": { ... },
  "error": null
}
```

失败时:
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "VERTEX_NOT_FOUND",
    "message": "起点节点不存在",
    "details": { "type": "Person", "filters": { "name": "不存在的名字" } }
  }
}
```

### path/traverse/pattern 统一响应

三种查询类型返回格式统一，包含完整路径信息：

```json
{
  "success": true,
  "data": {
    "queryType": "path",
    "paths": [
      {
        "nodes": [
          { "id": "v1", "type": "Person", "name": "张三", "age": 35 },
          { "id": "v2", "type": "Company", "name": "某科技公司", "industry": "IT" },
          { "id": "v3", "type": "City", "name": "北京", "population": 20000000 }
        ],
        "edges": [
          { "id": "e1", "from": "v1", "to": "v2", "label": "worksAt", "position": "工程师" },
          { "id": "e2", "from": "v2", "to": "v3", "label": "headquarteredIn", "since": 2010 }
        ],
        "totalHops": 2,
        "description": "Person(张三) -> worksAt -> Company(某科技公司) -> headquarteredIn -> City(北京)"
      }
    ],
    "totalPaths": 1
  }
}
```

**description 格式**: `类型(显示名) -> 边标签 -> 类型(显示名) -> ...`
- 显示名优先使用节点的 `name` 属性，无则使用 `id`

**pathType 字段**: 多路径 pattern 查询中标识路径所属的 MATCH 段变量（`p1`, `p2`, ...），单路径/path/traverse 中为 null。

### pattern 响应 — 多路径模式

当 pattern 查询包含多条边且生成多段 MATCH 时，`RETURN p1, p2, ...` 返回多路径结果，路径平铺为 `PathResult` 数组，每条路径带 `pathType` 标记所属变量：

```json
{
  "success": true,
  "data": {
    "queryType": "pattern",
    "paths": [
      {
        "nodes": [
          { "id": "player144", "type": "player", "name": "Shaquille O'Neal", "age": 47 },
          { "id": "player145", "type": "player", "name": "JaVale McGee", "age": 31 },
          { "id": "team201", "type": "team", "name": "Nuggets" }
        ],
        "edges": [
          { "from": "player144", "to": "player145", "label": "follow", "degree": 100 },
          { "from": "player145", "to": "team201", "label": "serve", "start_year": 2012, "end_year": 2015 }
        ],
        "pathType": "p1",
        "totalHops": 2,
        "description": "player(Shaquille O'Neal) -> follow -> player(JaVale McGee) -> serve -> team(Nuggets)"
      },
      {
        "nodes": [
          { "id": "player144", "type": "player", "name": "Shaquille O'Neal", "age": 47 },
          { "id": "team216", "type": "team", "name": "Cavaliers" }
        ],
        "edges": [
          { "from": "player144", "to": "team216", "label": "serve", "start_year": 2009, "end_year": 2010 }
        ],
        "pathType": "p2",
        "totalHops": 1,
        "description": "player(Shaquille O'Neal) -> serve -> team(Cavaliers)"
      }
    ],
    "totalPaths": 2
  }
}
```

**`pathType` 字段**：多路径模式中标识该路径对应的 MATCH 段变量名（`p1`, `p2`, ...）。单路径/path/traverse 查询中为 `null`。

三种场景的响应格式：

| 场景 | nGQL RETURN | response.data |
|------|-------------|---------------|
| 单路径 | `RETURN p` | `{ "paths": [PathResult], "totalPaths": N }` (pathType=null) |
| 多路径 | `RETURN p1, p2` | `{ "paths": [{pathType:p1,...}, {pathType:p2,...}], "totalPaths": N }` |
| select | `RETURN var` | `{ "nodes": [NodeResult], "totalResults": N }` |

### traverse 响应 (resultScope=nodes)

```json
{
  "success": true,
  "data": {
    "queryType": "traverse",
    "nodes": [
      { "id": "v2", "type": "Company", "name": "某公司" },
      { "id": "v3", "type": "City", "name": "北京" }
    ],
    "byHop": {
      "1": ["v2"],
      "2": ["v3"]
    }
  }
}
```

### 空结果响应

查询返回0条时，响应中仍包含完整结构：

```json
{
  "success": true,
  "data": {
    "queryType": "path",
    "paths": [],
    "totalPaths": 0
  }
}
```

## 后端适配

统一JSON输入 → 各后端自行实现转换为原生查询语言：
- SPARQL后端: JSON → SPARQL
- NebulaGraph后端: JSON → nGQL

### NebulaGraph nGQL 生成规则

**filter格式**: 分两种上下文：

1. **path / traverse 查询 (MATCH 不带命名边)**: `{var}.{tag}.{prop}` 格式
   - 例如: `src.player.name == 'Blake Griffin'`

2. **pattern 查询 (MATCH 带命名边 eN)**: `{var}.{prop}` Cypher 风格
   - 例如: `person1.name == 'Blake Griffin'`
   - 类型已在 MATCH 模式中声明，WHERE 中无需重复 tag

**path query**:
```
MATCH p = (src:{sourceType})-[*{minHops}..{maxHops}]->(dst:{targetType}) WHERE {filters} RETURN p LIMIT {limit}
```

**traverse query**:
```
MATCH p = (src:{sourceType})-[*{minHops}..{maxHops}]->(dst) WHERE {filters} RETURN p LIMIT {limit}
```

**pattern query** — 多段 MATCH + WITH 链模式：

每条边(`edges`)构成一个 MATCH 段，但连续相连的边（`edges[i].from == edges[i-1].to`）合并为一条 MATCH 链。节点类型只在首次出现时声明，后续复用时不重复声明。

**生成算法**:
```
1. 按 edges 顺序分组为链：若 edges[i].from == edges[i-1].to 则同链
2. 每条链生成一个 MATCH 段
3. 段间通过 WITH 链传递变量
```

**场景示例**:

1. **简单单边**:
   ```sql
   -- edges=[person→team]
   MATCH p1=(person:player)-[e1:serve]->(team:team) WHERE person.name == 'Blake' RETURN p1
   ```

2. **链式** (edges=[a→b, b→c]) — 合并为一条 MATCH:
   ```sql
   -- edges[0].from=a, edges[0].to=b → edges[1].from=b  → 同链
   MATCH p1=(a:player)-[e1:follow]->(b:player)-[e2:serve]->(c:team) RETURN p1
   ```

3. **多源共享目标** (edges=[a→c, b→c]) — 两条 MATCH:
   ```sql
   -- edges[0].to=c ≠ edges[1].from=b  → 不同链
   MATCH p1=(a:player)-[e1:serve]->(c:team) WHERE a.name == 'Blake'
   WITH a, c, e1, p1
   MATCH p2=(b:player)-[e2:serve]->(c) WHERE b.name == 'Tony'
   RETURN p1, p2
   ```

4. **星型** (edges=[a→b, a→c]) — 两条 MATCH:
   ```sql
   -- edges[0].to=b ≠ edges[1].from=a  → 不同链
   MATCH p1=(a:player)-[e1:serve]->(b:team)
   WITH a, b, e1, p1
   MATCH p2=(a)-[e2:serve]->(c:team)
   RETURN p1, p2
   ```

5. **混合链+星型** (edges=[a→b, b→c, a→c]):
   ```sql
   -- a→b→c 同链合并为一条 MATCH, a→c 独立一条
   MATCH p1=(a:player)-[e1:follow]->(b:player)-[e2:serve]->(c:team)
   WITH a, b, e1, c, e2, p1
   MATCH p2=(a)-[e3:serve]->(c)
   RETURN p1, p2
   ```

6. **带 select**:
   ```sql
   -- select: ["team"]
   MATCH p1=(person:player)-[e1:serve]->(team:team) WHERE person.name == 'Blake' RETURN team
   ```

**$and / $or 过滤**:
```sql
-- 输入: "$or": [{ "name": "Blake Griffin" }, { "name": "Tony Parker" }]
MATCH p1=(person1:player)-[e1:serve]->(team:team) WHERE (person1.name == 'Blake Griffin' OR person1.name == 'Tony Parker')
WITH ...
MATCH p2=...
RETURN p1, p2
```

### dryRun 模式

设置 `dryRun: true` 或 `dryRun: "true"` 时，API仅返回生成的查询语句，不执行：

```bash
curl -X POST http://localhost:9000/graph/nebula-repo/query \
  -H "Content-Type: application/json" \
  -d '{"queryType": "path", "dryRun": true, "source": {"type": "player", "filters": {"name": "Chris Paul"}}, "target": {"type": "team"}}'
```

返回:
```json
{
  "success": true,
  "data": {
    "sparql": "MATCH p = (src:player)-[*1..5]->(dst:team) WHERE src.player.name == 'Chris Paul' RETURN p LIMIT 10"
  }
}
```

## 错误码

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

## 配置示例 (config.yaml)

```yaml
default-repository: test-repo
repositories:
  test-repo:
    type: sparql
    sparql:
      ontologyPath: "G:/source/test/graph-search-api/graph.ttl"
      endpoint: "http://ontop3-svc-clone-kbp-dev.xyftest.hisense.com/sparql"
  nebula-repo:
    type: nebula
    nebula:
      address: "10.19.197.59:9669"
      username: "root"
      password: "nebula123"
      space: "demo_basketballplayer"
      poolSize: 10
      timeout: 10000
```

## 不支持

- 分页查询（使用limit限制）
- 认证/授权