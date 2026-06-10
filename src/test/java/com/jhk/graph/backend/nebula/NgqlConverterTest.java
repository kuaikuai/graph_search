package com.jhk.graph.backend.nebula;

import com.jhk.graph.dto.request.GraphQueryRequest;
import com.jhk.graph.dto.request.PathElement;
import com.jhk.graph.dto.request.PatternEdge;
import com.jhk.graph.dto.request.PatternVertex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NgqlConverterTest {

    private NgqlConverter converter;

    @BeforeEach
    void setUp() {
        converter = new NgqlConverter();
    }

    @Test
    void testConvertPathQuery() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("path");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("Organization");
        source.setFilters(Map.of("name", "冰冷事业部"));
        request.setSource(source);

        GraphQueryRequest.SourceTarget target = new GraphQueryRequest.SourceTarget();
        target.setType("PainPoint");
        request.setTarget(target);

        request.setMaxHops(5);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p = (src:Organization)"));
        assertTrue(ngql.contains("PainPoint"));
        assertTrue(ngql.contains("src.Organization.name"));
        assertTrue(ngql.contains("RETURN p"));
    }

    @Test
    void testConvertPathQueryWithoutFilters() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("path");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("Organization");
        request.setSource(source);

        GraphQueryRequest.SourceTarget target = new GraphQueryRequest.SourceTarget();
        target.setType("PainPoint");
        request.setTarget(target);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p = (src:Organization)"));
        assertTrue(ngql.contains("(dst:PainPoint"));
        assertTrue(ngql.contains("RETURN p"));
    }

    @Test
    void testConvertTraverseQuery() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("traverse");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("Organization");
        request.setSource(source);

        request.setMinHops(1);
        request.setMaxHops(3);
        request.setDirection("out");

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH"));
        assertTrue(ngql.contains("[*1..3]->"));
    }

    @Test
    void testConvertPatternQuery() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("person", "Person", Map.of("name", "张三")),
            new PatternVertex("company", "Company", null)
        };
        request.setNodes(nodes);

        PatternEdge[] edges = new PatternEdge[]{
            new PatternEdge("person", "company", "worksAt")
        };
        request.setEdges(edges);

        request.setSelect(new String[]{"person", "company"});

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH"));
        assertTrue(ngql.contains("Person"));
        assertTrue(ngql.contains("worksAt"));
        assertTrue(ngql.contains("Company"));
        assertTrue(ngql.contains("RETURN"));
    }

    @Test
    void testConvertPatternQueryWithFilters() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("p", "Person", Map.of("name", "张三")),
            new PatternVertex("c", "Company", Map.of("industry", "IT"))
        };
        request.setNodes(nodes);

        PatternEdge[] edges = new PatternEdge[]{
            new PatternEdge("p", "c", "worksAt")
        };
        request.setEdges(edges);

        request.setSelect(new String[]{"p", "c"});

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("WHERE"));
        assertTrue(ngql.contains("p.Person.name"));
        assertTrue(ngql.contains("c.Company.industry"));
    }

    @Test
    void testUnsupportedQueryType() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("unsupported");

        assertThrows(IllegalArgumentException.class, () -> converter.toNgql(request));
    }

    @Test
    void testConvertPatternWithVerticesAndEdges() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("person", "Person", Map.of("name", "张三")),
            new PatternVertex("company", "Company", Map.of("industry", "IT")),
            new PatternVertex("city", "City", null)
        };
        request.setNodes(nodes);

        PatternEdge[] edges = new PatternEdge[]{
            new PatternEdge("person", "company", "worksAt"),
            new PatternEdge("company", "city", "locatedIn")
        };
        request.setEdges(edges);

        request.setSelect(new String[]{"person", "company", "city"});

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH"));
        assertTrue(ngql.contains("(person:Person)"));
        assertTrue(ngql.contains("(company:Company)"));
        assertTrue(ngql.contains("(city:City)"));
        assertTrue(ngql.contains("[worksAt]->"));
        assertTrue(ngql.contains("[locatedIn]->"));
        assertTrue(ngql.contains("person.Person.name"));
        assertTrue(ngql.contains("company.Company.industry"));
        assertTrue(ngql.contains("RETURN person, company, city"));
    }

    @Test
    void testConvertPatternWithVariableHops() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("e1", "Employee", Map.of("name", "张三")),
            new PatternVertex("mgr", "Manager", null)
        };
        request.setNodes(nodes);

        PatternEdge edge = new PatternEdge("e1", "mgr", "reportsTo");
        edge.setMinHops(1);
        edge.setMaxHops(2);
        request.setEdges(new PatternEdge[]{edge});

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("[reportsTo*1..2]->"));
    }

    @Test
    void testConvertPatternWithEdgeDirection() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("emp", "Employee", null),
            new PatternVertex("mgr", "Manager", null)
        };
        request.setNodes(nodes);

        PatternEdge edge = new PatternEdge("emp", "mgr", "reportsTo");
        edge.setDirection("in");
        request.setEdges(new PatternEdge[]{edge});

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("<-[reportsTo]-"));
    }
}