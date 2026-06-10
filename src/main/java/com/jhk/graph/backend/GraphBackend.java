package com.jhk.graph.backend;

import com.jhk.graph.dto.request.GraphQueryRequest;
import com.jhk.graph.dto.response.GraphQueryResponse;

/**
 * 图数据库后端适配接口
 * 统一 JSON 输入 → 各后端自行实现转换为原生查询语言
 */
public interface GraphBackend {

    /**
     * 执行图查询
     * @param request 统一格式的查询请求
     * @return 统一格式的查询响应
     */
    GraphQueryResponse execute(GraphQueryRequest request);

    /**
     * 获取后端类型标识
     */
    String getType();

    /**
     * 仅生成 SPARQL 查询字符串，不执行（用于 dryRun 模式）
     * @param request 统一格式的查询请求
     * @return 生成的 SPARQL 查询字符串
     * @throws UnsupportedOperationException 如果后端不支持该查询类型
     */
    String buildSparql(GraphQueryRequest request);
}
