package com.jhk.graph.backend.sparql;

import com.jhk.graph.config.BackendConfig;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统一的 SPARQL 构建器。
 * 从配置读取 PREFIX 映射，typeDecl/edgeDecl 使用可配置的 prefix 名，
 * 不依赖硬编码命名空间。
 */
public class SparqlBuilder {

    private final Map<String, String> prefixes;
    private final String typePrefix;  // prefix name for rdf:type (e.g., "bacls")
    private final String propPrefix;  // prefix name for property edges (e.g., "baprop")

    public SparqlBuilder(BackendConfig.SparqlProperties config) {
        this.prefixes = config.getPrefixes();
        this.typePrefix = config.getTypePrefix();
        this.propPrefix = config.getPropPrefix();
    }

    // ---- PREFIX ----

    /** 生成 PREFIX 声明块 */
    public String buildPrefixBlock() {
        return prefixes.entrySet().stream()
            .map(e -> "PREFIX " + e.getKey() + ": <" + e.getValue() + ">\n")
            .collect(Collectors.joining());
    }

    // ---- Type / Property declarations ----

    /** 生成 RDF type 声明: ?var rdf:type {typePrefix}:TypeName . */
    public String typeDecl(String var, String type) {
        return "  " + var + " rdf:type " + typePrefix + ":" + type + " .\n";
    }

    /** 生成属性边声明: ?from {propPrefix}:label ?to . */
    public String edgeDecl(String from, String label, String to) {
        return "  " + from + " " + propPrefix + ":" + label + " " + to + " .\n";
    }

    /** 属性类型约束 SPARQL 片段: ?prop rdf:type {propPrefix}:Property */
    public String propTypeConstraint(String var) {
        return "  " + var + " rdf:type " + propPrefix + ":Property .\n";
    }

    // ---- Common SPARQL snippets ----

    /** 属性发现查询（已知属性列表） */
    public String findPropertiesSparql() {
        return buildPrefixBlock()
            + "SELECT DISTINCT ?prop WHERE {\n"
            + propTypeConstraint("?prop")
            + "}";
    }

    /** 实例属性查询: SELECT DISTINCT ?prop ?val WHERE { <uri> ?prop ?val } */
    public String instancePropertiesSparql(String uri) {
        return buildPrefixBlock()
            + "SELECT DISTINCT ?prop ?val WHERE {\n"
            + "  <" + uri + "> ?prop ?val .\n"
            + propTypeConstraint("?prop")
            + "}";
    }

    // ---- Accessors ----
    public String getTypePrefix() { return typePrefix; }
    public String getPropPrefix() { return propPrefix; }
    public Map<String, String> getPrefixes() { return prefixes; }
}
