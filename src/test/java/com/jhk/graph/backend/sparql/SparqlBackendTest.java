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
 * SparqlBackend TDD tests - traverse and pattern query types
 * RED: Tests written to define expected behavior
 * GREEN: Minimal implementation to pass tests
 * REFACTOR: Clean up
 */
class SparqlBackendTest {

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

            # 实例数据
            bacls:Org1  rdf:type  bacls:Organization ;
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

    // ===== traverse query tests =====

    @Test
    void execute_withTraverseQueryType_returnsTraverseResponse() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("traverse");
        request.setSource("bacls:Org1");
        request.setMaxHops(3);
        request.setDirection("out");
        request.setResultScope("nodes");

        GraphQueryResponse response = newBackend().execute(request);

        assertNotNull(response);
        assertEquals("traverse", response.getQueryType());
        assertTrue(response.getNodes() != null || response.getByHop() != null,
                "traverse response should have nodes or byHop");
    }

    @Test
    void execute_withTraverseQueryType_andPathsResultScope_returnsPaths() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("traverse");
        request.setSource("bacls:Org1");
        request.setMaxHops(2);
        request.setDirection("out");
        request.setResultScope("paths");

        GraphQueryResponse response = newBackend().execute(request);

        assertNotNull(response);
        assertEquals("traverse", response.getQueryType());
        assertNotNull(response.getPaths());
    }

    @Test
    void execute_withTraverseQueryType_withMaxHops_limitsResults() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("traverse");
        request.setSource("bacls:Org1");
        request.setMaxHops(1);
        request.setDirection("out");

        GraphQueryResponse response = newBackend().execute(request);

        assertNotNull(response);
        assertEquals("traverse", response.getQueryType());
        assertNotNull(response.getNodes());
    }

    @Test
    void execute_withTraverseQueryType_withEdgeLabels_filtersByEdge() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("traverse");
        request.setSource("bacls:Org1");
        request.setMaxHops(2);
        request.setDirection("out");
        request.setEdgeLabels(new String[]{"involvesScenario"});

        GraphQueryResponse response = newBackend().execute(request);

        assertNotNull(response);
        assertEquals("traverse", response.getQueryType());
    }

    // ===== pattern query tests =====

    @Test
    void execute_withPatternQueryType_returnsPatternResponse() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");
        request.setPath(new PathElement[]{
                PathElement.node("Organization", null, null),
                PathElement.edge("involvesScenario", null),
                PathElement.node("AnalysisScenario", null, null)
        });
        request.setSelect(new String[]{"org", "scenario"});

        GraphQueryResponse response = newBackend().execute(request);

        assertNotNull(response);
        assertEquals("pattern", response.getQueryType());
        assertNotNull(response.getPaths(), "pattern response should have paths");
    }

    @Test
    void execute_withPatternQueryType_withNodeAlias_usesAlias() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");
        request.setPath(new PathElement[]{
                PathElement.node("Organization", "org", null),
                PathElement.edge("involvesScenario", null),
                PathElement.node("AnalysisScenario", "scenario", null)
        });
        request.setSelect(new String[]{"org", "scenario"});

        GraphQueryResponse response = newBackend().execute(request);

        assertNotNull(response);
        assertEquals("pattern", response.getQueryType());
        assertNotNull(response.getNodes());
        assertFalse(response.getNodes().isEmpty());
    }

    @Test
    void execute_withPatternQueryType_longerPath_returnsResults() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");
        request.setPath(new PathElement[]{
                PathElement.node("Organization", "org", null),
                PathElement.edge("involvesScenario", null),
                PathElement.node("AnalysisScenario", "scenario", null),
                PathElement.edge("involvesMetricDimensionUnit", null),
                PathElement.node("MetricDimensionUnit", "mdu", null),
                PathElement.edge("hasAbnormalRule", null),
                PathElement.node("AbnormalRule", "rule", null)
        });
        request.setSelect(new String[]{"org", "scenario", "mdu", "rule"});

        GraphQueryResponse response = newBackend().execute(request);

        assertNotNull(response);
        assertEquals("pattern", response.getQueryType());
        assertNotNull(response.getPaths());
    }

    // ===== error handling =====

    @Test
    void execute_withUnsupportedQueryType_throwsInvalidQueryType() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("unsupported");
        GraphQueryException ex = assertThrows(GraphQueryException.class, () -> newBackend().execute(request));
        assertEquals("INVALID_QUERY_TYPE", ex.getCode());
    }
}
