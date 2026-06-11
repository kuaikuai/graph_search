package com.jhk.graph.backend.nebula;

import com.jhk.graph.dto.request.GraphQueryRequest;
import com.jhk.graph.dto.request.PathElement;
import com.jhk.graph.dto.request.PatternEdge;
import com.jhk.graph.dto.request.PatternVertex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NgqlConverterTest {

    private NgqlConverter converter;

    @BeforeEach
    void setUp() {
        converter = new NgqlConverter();
    }

    // ========== PATH QUERY TESTS ==========

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
    void testConvertPathQueryWithSourceFilterTargetTypeAndLimit() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("path");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("Person");
        source.setFilters(Map.of("age", 30));
        request.setSource(source);

        GraphQueryRequest.SourceTarget target = new GraphQueryRequest.SourceTarget();
        target.setType("Company");
        request.setTarget(target);

        request.setMaxHops(3);
        request.setLimit(5);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p = (src:Person)"));
        assertTrue(ngql.contains("(dst:Company)"));
        assertTrue(ngql.contains("src.Person.age"));
        assertTrue(ngql.contains("RETURN p"));
        // Path query currently does not support LIMIT
    }

    @Test
    void testConvertPathQueryWithDirectionIn() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("path");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("Employee");
        request.setSource(source);

        request.setDirection("in");
        request.setMaxHops(3);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p = (src:Employee)<-[*"));
        assertTrue(ngql.contains("RETURN p"));
    }

    @Test
    void testConvertPathQueryWithNoTarget() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("path");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("Person");
        source.setFilters(Map.of("name", "张三"));
        request.setSource(source);

        request.setMaxHops(5);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p = (src:Person)"));
        assertTrue(ngql.contains("-[*1..5]->(dst)"));
        assertTrue(ngql.contains("src.Person.name"));
        assertTrue(ngql.contains("RETURN p"));
    }

    @Test
    void testConvertPathQueryWithNumericFilter() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("path");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("Product");
        source.setFilters(Map.of("price", Map.of("$gt", 100)));
        request.setSource(source);

        GraphQueryRequest.SourceTarget target = new GraphQueryRequest.SourceTarget();
        target.setType("Category");
        request.setTarget(target);

        request.setMaxHops(3);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p = (src:Product)"));
        assertTrue(ngql.contains("(dst:Category)"));
        assertTrue(ngql.contains("src.Product.price > 100"));
        assertTrue(ngql.contains("RETURN p"));
    }

    @Test
    void testConvertPathQueryWithAndOrFilters() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("path");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("User");
        source.setFilters(Map.of(
            "$and", List.of(
                Map.of("status", "active"),
                Map.of("age", Map.of("$gte", 18))
            )
        ));
        request.setSource(source);

        GraphQueryRequest.SourceTarget target = new GraphQueryRequest.SourceTarget();
        target.setType("Order");
        target.setFilters(Map.of(
            "$or", List.of(
                Map.of("status", "pending"),
                Map.of("status", "processing")
            )
        ));
        request.setTarget(target);

        request.setMaxHops(5);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p = (src:User)"));
        assertTrue(ngql.contains("(dst:Order)"));
        assertTrue(ngql.contains("src.User.status == 'active'"));
        assertTrue(ngql.contains("src.User.age >= 18"));
        assertTrue(ngql.contains("dst.Order.status == 'pending'"));
        assertTrue(ngql.contains("dst.Order.status == 'processing'"));
        assertTrue(ngql.contains("RETURN p"));
    }

    // ========== TRAVERSE QUERY TESTS ==========

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
    void testConvertTraverseQueryWithSourceFilter() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("traverse");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("Organization");
        source.setFilters(Map.of("name", "冰冷事业部"));
        request.setSource(source);

        request.setMinHops(1);
        request.setMaxHops(3);
        request.setDirection("out");

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p = (src:Organization)"));
        assertTrue(ngql.contains("[*1..3]->"));
        assertTrue(ngql.contains("WHERE"));
        assertTrue(ngql.contains("src.Organization.name"));
        assertTrue(ngql.contains("RETURN p"));
    }

    @Test
    void testConvertTraverseQueryWithDirectionIn() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("traverse");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("Employee");
        request.setSource(source);

        request.setMinHops(1);
        request.setMaxHops(3);
        request.setDirection("in");

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p = (src:Employee)<-[*1..3]-"));
        assertTrue(ngql.contains("RETURN p"));
    }

    @Test
    void testConvertTraverseQueryWithDirectionBoth() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("traverse");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("Person");
        request.setSource(source);

        request.setMinHops(2);
        request.setMaxHops(5);
        request.setDirection("both");

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p = (src:Person)-[*2..5]-"));
        assertTrue(ngql.contains("RETURN p"));
    }

    @Test
    void testConvertTraverseQueryWithLimit() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("traverse");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("Company");
        request.setSource(source);

        request.setMinHops(1);
        request.setMaxHops(3);
        request.setLimit(20);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("RETURN p LIMIT 20"));
    }

    @Test
    void testConvertTraverseQueryWithAndFilter() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("traverse");

        GraphQueryRequest.SourceTarget source = new GraphQueryRequest.SourceTarget();
        source.setType("User");
        source.setFilters(Map.of(
            "$and", List.of(
                Map.of("role", "admin"),
                Map.of("active", true)
            )
        ));
        request.setSource(source);

        request.setMinHops(1);
        request.setMaxHops(2);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p = (src:User)"));
        assertTrue(ngql.contains("WHERE"));
        assertTrue(ngql.contains("src.User.role == 'admin'"));
        assertTrue(ngql.contains("src.User.active == 'true'"));
        assertTrue(ngql.contains("RETURN p"));
    }

    // ========== PATTERN QUERY TESTS ==========

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
    void testConvertPatternSingleEdgeNoFiltersNoSelect() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("a", "Person", null),
            new PatternVertex("b", "Company", null)
        };
        request.setNodes(nodes);

        PatternEdge[] edges = new PatternEdge[]{
            new PatternEdge("a", "b", "worksAt")
        };
        request.setEdges(edges);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p1=(a:Person)-[e1:worksAt]->(b:Company)"));
        assertTrue(ngql.contains("RETURN p1"));
        assertTrue(ngql.contains("LIMIT 10"));
    }

    @Test
    void testConvertPatternSingleEdgeWithFilterNoSelect() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("p", "Person", Map.of("name", "张三")),
            new PatternVertex("c", "Company", null)
        };
        request.setNodes(nodes);

        PatternEdge[] edges = new PatternEdge[]{
            new PatternEdge("p", "c", "worksAt")
        };
        request.setEdges(edges);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p1=(p:Person)-[e1:worksAt]->(c:Company)"));
        assertTrue(ngql.contains("WHERE"));
        assertTrue(ngql.contains("p.Person.name"));
        assertTrue(ngql.contains("RETURN p1"));
    }

    @Test
    void testConvertPatternChainABC() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("a", "A", null),
            new PatternVertex("b", "B", null),
            new PatternVertex("c", "C", null)
        };
        request.setNodes(nodes);

        PatternEdge[] edges = new PatternEdge[]{
            new PatternEdge("a", "b", "rel1"),
            new PatternEdge("b", "c", "rel2")
        };
        request.setEdges(edges);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p1=(a:A)-[e1:rel1]->(b:B)-[e2:rel2]->(c:C)"));
        assertTrue(ngql.contains("RETURN p1"));
    }

    @Test
    void testConvertPatternMultiSourceSharedTarget() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("a", "A", null),
            new PatternVertex("b", "B", null),
            new PatternVertex("c", "C", null)
        };
        request.setNodes(nodes);

        PatternEdge[] edges = new PatternEdge[]{
            new PatternEdge("a", "c", "rel1"),
            new PatternEdge("b", "c", "rel2")
        };
        request.setEdges(edges);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p1=(a:A)-[e1:rel1]->(c:C)"));
        assertTrue(ngql.contains("MATCH p2=(b:B)-[e2:rel2]->(c)"));
        assertTrue(ngql.contains(" WITH "));
        assertTrue(ngql.contains("RETURN p1, p2"));
    }

    @Test
    void testConvertPatternMixedChainAndStar() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("a", "A", null),
            new PatternVertex("b", "B", null),
            new PatternVertex("c", "C", null)
        };
        request.setNodes(nodes);

        PatternEdge[] edges = new PatternEdge[]{
            new PatternEdge("a", "b", "rel1"),
            new PatternEdge("b", "c", "rel2"),
            new PatternEdge("a", "c", "rel3")
        };
        request.setEdges(edges);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p1=(a:A)-[e1:rel1]->(b:B)-[e2:rel2]->(c:C)"));
        assertTrue(ngql.contains("MATCH p2=(a)-[e3:rel3]->(c)"));
        assertTrue(ngql.contains(" WITH "));
        assertTrue(ngql.contains("RETURN p1, p2"));
    }

    @Test
    void testConvertPatternWithOrFilter() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("p", "Person", Map.of(
                "$or", List.of(
                    Map.of("city", "北京"),
                    Map.of("city", "上海")
                )
            )),
            new PatternVertex("c", "Company", null)
        };
        request.setNodes(nodes);

        PatternEdge[] edges = new PatternEdge[]{
            new PatternEdge("p", "c", "worksAt")
        };
        request.setEdges(edges);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p1=(p:Person)-[e1:worksAt]->(c:Company)"));
        assertTrue(ngql.contains("WHERE"));
        assertTrue(ngql.contains("(p.Person.city == '北京' OR p.Person.city == '上海')"));
        assertTrue(ngql.contains("RETURN p1"));
    }

    @Test
    void testConvertPatternWithAndFilter() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("p", "Person", Map.of(
                "$and", List.of(
                    Map.of("age", Map.of("$gte", 18)),
                    Map.of("status", "active")
                )
            )),
            new PatternVertex("c", "Company", null)
        };
        request.setNodes(nodes);

        PatternEdge[] edges = new PatternEdge[]{
            new PatternEdge("p", "c", "worksAt")
        };
        request.setEdges(edges);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p1=(p:Person)-[e1:worksAt]->(c:Company)"));
        assertTrue(ngql.contains("WHERE"));
        assertTrue(ngql.contains("(p.Person.age >= 18 AND p.Person.status == 'active')"));
        assertTrue(ngql.contains("RETURN p1"));
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
        assertTrue(ngql.contains("[e1:reportsTo*1..2]->"));
    }

    @Test
    void testConvertPatternWithVariableHopsEdge() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("emp", "Employee", null),
            new PatternVertex("ceo", "CEO", null)
        };
        request.setNodes(nodes);

        PatternEdge edge = new PatternEdge("emp", "ceo", "reportsTo");
        edge.setMinHops(2);
        edge.setMaxHops(5);
        request.setEdges(new PatternEdge[]{edge});

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p1=(emp:Employee)-[e1:reportsTo*2..5]->(ceo:CEO)"));
        assertTrue(ngql.contains("RETURN p1"));
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
        assertTrue(ngql.contains("<-[e1:reportsTo]-"));
    }

    @Test
    void testConvertPatternWithEdgeDirectionIn() {
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
        assertTrue(ngql.contains("MATCH p1=(emp:Employee)<-[e1:reportsTo]-(mgr:Manager)"));
        assertTrue(ngql.contains("RETURN p1"));
    }

    @Test
    void testConvertPatternWithSelect() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("p", "Person", Map.of("name", "张三")),
            new PatternVertex("c", "Company", Map.of("name", "某公司"))
        };
        request.setNodes(nodes);

        PatternEdge[] edges = new PatternEdge[]{
            new PatternEdge("p", "c", "worksAt")
        };
        request.setEdges(edges);

        request.setSelect(new String[]{"p", "c"});

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p1=(p:Person)-[e1:worksAt]->(c:Company)"));
        assertTrue(ngql.contains("WHERE"));
        assertTrue(ngql.contains("p.Person.name"));
        assertTrue(ngql.contains("c.Company.name"));
        assertTrue(ngql.contains("RETURN p, c"));
    }

    @Test
    void testConvertPatternWithLimit() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("a", "A", null),
            new PatternVertex("b", "B", null)
        };
        request.setNodes(nodes);

        PatternEdge[] edges = new PatternEdge[]{
            new PatternEdge("a", "b", "rel")
        };
        request.setEdges(edges);

        request.setLimit(50);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("RETURN p1 LIMIT 50"));
    }

    @Test
    void testConvertPatternWithEdgeFilters() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("p", "Person", null),
            new PatternVertex("c", "Company", null)
        };
        request.setNodes(nodes);

        PatternEdge edge = new PatternEdge("p", "c", "worksAt");
        edge.setFilters(Map.of("since", 2020));
        request.setEdges(new PatternEdge[]{edge});

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p1=(p:Person)-[e1:worksAt]->(c:Company)"));
        assertTrue(ngql.contains("WHERE"));
        assertTrue(ngql.contains("e1.since == '2020'"));
        assertTrue(ngql.contains("RETURN p1"));
    }

    @Test
    void testConvertPatternWithNodeFiltersOnTargetNode() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("p", "Person", null),
            new PatternVertex("c", "Company", Map.of("industry", "IT"))
        };
        request.setNodes(nodes);

        PatternEdge[] edges = new PatternEdge[]{
            new PatternEdge("p", "c", "worksAt")
        };
        request.setEdges(edges);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p1=(p:Person)-[e1:worksAt]->(c:Company)"));
        assertTrue(ngql.contains("WHERE"));
        assertTrue(ngql.contains("c.Company.industry"));
        assertTrue(ngql.contains("RETURN p1"));
    }

    @Test
    void testConvertPatternComplexChainThreeEdges() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("pattern");

        PatternVertex[] nodes = new PatternVertex[]{
            new PatternVertex("a", "A", null),
            new PatternVertex("b", "B", null),
            new PatternVertex("c", "C", null),
            new PatternVertex("d", "D", null)
        };
        request.setNodes(nodes);

        PatternEdge[] edges = new PatternEdge[]{
            new PatternEdge("a", "b", "rel1"),
            new PatternEdge("b", "c", "rel2"),
            new PatternEdge("c", "d", "rel3")
        };
        request.setEdges(edges);

        String ngql = converter.toNgql(request);
        assertTrue(ngql.contains("MATCH p1=(a:A)-[e1:rel1]->(b:B)-[e2:rel2]->(c:C)-[e3:rel3]->(d:D)"));
        assertTrue(ngql.contains("RETURN p1"));
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
        assertTrue(ngql.contains("[e1:worksAt]->"));
        assertTrue(ngql.contains("[e2:locatedIn]->"));
        assertTrue(ngql.contains("person.Person.name"));
        assertTrue(ngql.contains("company.Company.industry"));
        assertTrue(ngql.contains("RETURN person, company, city"));
    }

    @Test
    void testUnsupportedQueryType() {
        GraphQueryRequest request = new GraphQueryRequest();
        request.setQueryType("unsupported");

        assertThrows(IllegalArgumentException.class, () -> converter.toNgql(request));
    }
}
