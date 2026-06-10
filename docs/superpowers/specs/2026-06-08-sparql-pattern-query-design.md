# SPARQL Pattern Query Design

**日期**: 2026-06-08
**状态**: 已实现
**后端**: SparqlBackend

---

## 1. 概述

Pattern Query API 支持通过声明式结构（vertices + edges）定义图模式查询，替代传统的路径数组（path）结构。新结构更易于程序生成和维护。

**SPARQL Endpoint**: `http://ontop3-svc-clone-kbp-dev.xyftest.hisense.com/sparql`

---

## 2. 请求结构

### 2.1 新结构：vertices + edges

```json
{
  "queryType": "pattern",
  "select": ["org", "target"],
  "vertices": [
    {
      "id": "org",
      "type": "Organization",
      "filters": {
        "orgOrgName": "研发部"
      }
    },
    {
      "id": "scenario",
      "type": "AnalysisScenario"
    },
    {
      "id": "target",
      "type": "PainPoint"
    }
  ],
  "edges": [
    {
      "from": "org",
      "to": "scenario",
      "label": "involvesScenario"
    },
    {
      "from": "scenario",
      "to": "target",
      "label": "correspondsToPainPoint"
    }
  ],
  "where": "FILTER(?org != ?target)"
}
```

### 2.2 字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| queryType | string | 是 | 固定为 "pattern" |
| select | string[] | 否 | 要返回的变量名列表，默认返回所有顶点 ID |
| vertices | PatternVertex[] | 是* | 顶点定义数组（*与 path 二选一） |
| edges | PatternEdge[] | 是* | 边定义数组（*与 path 二选一） |
| where | string | 否 | SPARQL FILTER 表达式 |
| path | PathElement[] | 是* | 传统路径结构（*与 vertices/edges 二选一） |

### 2.3 PatternVertex

```java
public class PatternVertex {
    private String id;           // 顶点唯一标识，用于边引用
    private String type;         // RDF 类型 (对应 bacls: 前缀)
    private Map<String, Object> filters;  // 属性过滤器
}
```

### 2.4 PatternEdge

```java
public class PatternEdge {
    private String from;          // 源顶点 ID
    private String to;            // 目标顶点 ID
    private String label;         // 边类型 (对应 baprop: 前缀)
    private String direction;     // 方向: out | in | both (默认: out)
    private HopsRange hops;       // 跳数范围（SPARQL 不支持，仅用于 API 统一）
    private Map<String, Object> filters;  // 边属性过滤器
}

public static class HopsRange {
    private Integer min;
    private Integer max;
}
```

---

## 3. SPARQL 生成规则

### 3.1 PREFIX 声明

```sparql
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX bacls: <http://www.jhk.com/finance/business-analysis/class/>
PREFIX baprop: <http://www.jhk.com/finance/business-analysis/property/>
```

### 3.2 SELECT 子句

- 若指定 `select` 字段，返回指定变量
- 否则返回所有顶点 ID 变量

```sparql
SELECT DISTINCT ?org ?scenario ?target
```

### 3.3 顶点类型约束

每个顶点生成类型声明三元组：

```sparql
?org rdf:type bacls:Organization .
```

带过滤器的顶点：

```sparql
?org rdf:type bacls:Organization .
?org baprop:orgOrgName "研发部" .
```

### 3.4 边三元组

每个边定义生成 SPARQL 三元组：

```sparql
?org baprop:involvesScenario ?scenario .
?scenario baprop:correspondsToPainPoint ?target .
```

### 3.5 WHERE 子句

额外 FILTER 条件追加到 WHERE 块末尾：

```sparql
FILTER(?org != ?target)
```

---

## 4. 完整示例

### 请求

```json
{
  "queryType": "pattern",
  "vertices": [
    {"id": "org", "type": "Organization", "filters": {"orgOrgName": "研发部"}},
    {"id": "scenario", "type": "AnalysisScenario"},
    {"id": "mdu", "type": "MetricDimensionUnit"},
    {"id": "rule", "type": "AbnormalRule"},
    {"id": "target", "type": "PainPoint"}
  ],
  "edges": [
    {"from": "org", "to": "scenario", "label": "involvesScenario"},
    {"from": "scenario", "to": "mdu", "label": "involvesMetricDimensionUnit"},
    {"from": "mdu", "to": "rule", "label": "hasAbnormalRule"},
    {"from": "rule", "to": "target", "label": "correspondsToPainPoint"}
  ],
  "where": "FILTER(?org != ?target)"
}
```

### 生成的 SPARQL

```sparql
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX bacls: <http://www.jhk.com/finance/business-analysis/class/>
PREFIX baprop: <http://www.jhk.com/finance/business-analysis/property/>

SELECT DISTINCT ?org ?scenario ?mdu ?rule ?target WHERE {
  ?org rdf:type bacls:Organization .
  ?org baprop:orgOrgName "研发部" .
  ?scenario rdf:type bacls:AnalysisScenario .
  ?mdu rdf:type bacls:MetricDimensionUnit .
  ?rule rdf:type bacls:AbnormalRule .
  ?target rdf:type bacls:PainPoint .
  ?org baprop:involvesScenario ?scenario .
  ?scenario baprop:involvesMetricDimensionUnit ?mdu .
  ?mdu baprop:hasAbnormalRule ?rule .
  ?rule baprop:correspondsToPainPoint ?target .
  FILTER(?org != ?target)
}
```

---

## 5. 响应格式

Pattern query 返回与 path/traverse 相同的响应格式：

```json
{
  "queryType": "pattern",
  "nodes": [
    {"id": "http://example.org/org/1", "type": "Organization"},
    {"id": "http://example.org/scenario/1", "type": "AnalysisScenario"},
    {"id": "http://example.org/target/1", "type": "PainPoint"}
  ],
  "paths": [
    {
      "nodes": [...],
      "edges": [...],
      "totalHops": 2
    }
  ],
  "totalPaths": 1
}
```

### 5.1 NodeResult

```java
public static class NodeResult extends LinkedHashMap<String, Object> {
    private static final String ID_KEY = "id";
    private static final String TYPE_KEY = "type";

    public NodeResult(String id, String type, Map<String, Object> properties) {
        put(ID_KEY, id);
        put(TYPE_KEY, type);
        putAll(properties);
    }
}
```

### 5.2 EdgeResult

```java
public static class EdgeResult extends LinkedHashMap<String, Object> {
    private static final String ID_KEY = "id";
    private static final String FROM_KEY = "from";
    private static final String TO_KEY = "to";
    private static final String LABEL_KEY = "label";

    public EdgeResult(String id, String from, String to, String label, Map<String, Object> properties) {
        put(ID_KEY, id);
        put(FROM_KEY, from);
        put(TO_KEY, to);
        put(LABEL_KEY, label);
        putAll(properties);
    }
}
```

---

## 6. 实现类

| 类 | 职责 |
|----|------|
| `SparqlBackend` | SPARQL 查询执行器 |
| `PatternVertex` | 顶点定义 DTO |
| `PatternEdge` | 边定义 DTO |
| `FilterExpressionBuilder` | 属性过滤器解析 |

---

## 7. 限制

1. **hops 参数**: SPARQL 不支持 NebulaGraph 那样的多跳路径语法，`hops` 字段仅用于 API 统一，不生成实际查询
2. **边属性过滤器**: RDF 三元组模型不直接支持边属性，edge.filters 暂不实现
3. **direction**: 仅支持 `out`（默认），`in` 和 `both` 待扩展

---

## 8. 设计决策

1. **vertices/edges vs path**: 新结构声明式更强，程序生成更容易
2. **响应格式统一**: Pattern 与 path/traverse 共用同一响应结构，便于前端处理
3. **类型前缀**: 固定使用 `bacls:` 和 `baprop:` 前缀，与业务本体一致
