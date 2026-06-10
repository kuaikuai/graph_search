package com.jhk.graph.backend.sparql;

import com.jhk.graph.config.BackendConfig.SparqlProperties;
import com.jhk.graph.dto.request.GraphQueryRequest;
import com.jhk.graph.dto.request.PathElement;
import com.jhk.graph.dto.response.GraphQueryResponse;
import com.jhk.graph.exception.GraphQueryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for path query with filters
 */
class PathQueryFilterTest {

    @TempDir
    Path tempDir;

    private Path ontologyPath;

    @BeforeEach
    void setUp() throws IOException {
        String ttl = """
            @prefix bacls: <http://www.jhk.com/finance/business-analysis/class/> .
            @prefix baprop: <http://www.jhk.com/finance/business-analysis/property/> .
            @prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs:  <http://www.w3.org/2000/01/rdf-schema#> .

            # 属性定义
            baprop:involvesScenario  rdf:type  baprop:Property ;
                                     rdfs:domain  bacls:Organization ;
                                     rdfs:range   bacls:AnalysisScenario .

            baprop:involvesMetricDimensionUnit  rdf:type  baprop:Property ;
                                                rdfs:domain  bacls:AnalysisScenario ;
                                                rdfs:range   bacls:MetricDimensionUnit .

            baprop:hasAbnormalRule  rdf:type  baprop:Property ;
                                    rdfs:domain  bacls:MetricDimensionUnit ;
                                    rdfs:range   bacls:AbnormalRule .

            baprop:correspondsToPainPoint  rdf:type  baprop:Property ;
                                           rdfs:domain  bacls:AbnormalRule ;
                                           rdfs:range   bacls:PainPoint .

            baprop:orgOrgName  rdf:type  baprop:Property ;
                                rdfs:domain  bacls:Organization ;
                                rdfs:range   rdfs:Literal .

            # 实例数据
            bacls:Org1  rdf:type  bacls:Organization ;
                        baprop:orgOrgName "冰冷事业部" ;
                        baprop:involvesScenario  bacls:Scenario1 .

            bacls:Scenario1  rdf:type  bacls:AnalysisScenario ;
                               baprop:involvesMetricDimensionUnit  bacls:Mdu1 .

            bacls:Mdu1  rdf:type  bacls:MetricDimensionUnit ;
                         baprop:hasAbnormalRule  bacls:Rule1 .

            bacls:Rule1  rdf:type  bacls:AbnormalRule ;
                          baprop:correspondsToPainPoint  bacls:Pp1 .

            bacls:Pp1  rdf:type  bacls:PainPoint .
            """;
        ontologyPath = tempDir.resolve("test-ontology.ttl");
        Files.writeString(ontologyPath, ttl);
    }

    private SparqlBackend newBackend() {
        SparqlProperties props = new SparqlProperties();
        props.setOntologyPath(ontologyPath.toString());
        props.setEndpoint("");
        return new SparqlBackend(props);
    }

    @Test
    void buildSparql_withPathQueryAndFilters_generatesCorrectSparql() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("path");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("Organization");
        source.setFilters(new java.util.HashMap<>() {{ put("orgOrgName", "冰冷事业部"); }});
        request.setSource(source);

        GraphQueryRequest.SourceTarget target = new GraphQueryRequest.SourceTarget();
        target.setType("PainPoint");
        request.setTarget(target);

        String sparql = newBackend().buildSparql(request);
        System.out.println("Generated SPARQL:\n" + sparql);

        assertNotNull(sparql);
        assertTrue(sparql.contains("orgOrgName"));
        assertTrue(sparql.contains("冰冷事业部"));
    }

    @Test
    void execute_withPathQueryAndFilters_returnsPaths() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("path");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("Organization");
        source.setFilters(new java.util.HashMap<>() {{ put("orgOrgName", "冰冷事业部"); }});
        request.setSource(source);

        GraphQueryRequest.SourceTarget target = new GraphQueryRequest.SourceTarget();
        target.setType("PainPoint");
        request.setTarget(target);

        GraphQueryResponse response = newBackend().execute(request);

        assertNotNull(response);
        assertEquals("path", response.getQueryType());
        assertNotNull(response.getPaths());
        assertFalse(response.getPaths().isEmpty(), "Should find path from Org1 to PainPoint");
    }

    @Test
    void execute_withPathQueryAndNonExistentFilters_throwsVertexNotFound() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("path");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("Organization");
        source.setFilters(new java.util.HashMap<>() {{ put("orgOrgName", "不存在的名称"); }});
        request.setSource(source);

        GraphQueryRequest.SourceTarget target = new GraphQueryRequest.SourceTarget();
        target.setType("PainPoint");
        request.setTarget(target);

        GraphQueryException ex = assertThrows(GraphQueryException.class, () -> newBackend().execute(request));
        assertEquals("VERTEX_NOT_FOUND", ex.getCode());
    }
}
