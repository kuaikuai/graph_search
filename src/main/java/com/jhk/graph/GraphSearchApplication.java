package com.jhk.graph;

import com.jhk.graph.backend.GraphBackend;
import com.jhk.graph.backend.nebula.NebulaBackend;
import com.jhk.graph.backend.sparql.SparqlBackend;
import com.jhk.graph.config.BackendConfig.NebulaProperties;
import com.jhk.graph.config.BackendConfig.SparqlProperties;
import com.jhk.graph.dto.request.GraphQueryRequest;
import com.jhk.graph.dto.response.ApiResponse;
import com.jhk.graph.dto.response.GraphQueryResponse;
import com.jhk.graph.exception.GraphQueryException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.ExceptionHandler;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class GraphSearchApplication {

    /** 按仓库名缓存 backend 实例 */
    private static final Map<String, GraphBackend> backendCache = new HashMap<>();

    /** 用于手动解析 JSON */
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 配置路径（用于缓存） */
    private static String configPath = null;

    public static void main(String[] args) throws Exception {
        // 解析命令行参数
        for (int i = 0; i < args.length; i++) {
            if ("-c".equals(args[i]) || "--config".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("Error: -c/--config requires a path argument");
                    printUsage();
                    System.exit(1);
                }
                configPath = args[++i];
            } else {
                System.err.println("Error: unknown argument: " + args[i]);
                printUsage();
                System.exit(1);
            }
        }

        // 加载配置
        System.out.println("=== Graph Search API ===");
        System.out.println("Loading config...");
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) loadConfig();
        if (config == null) {
            System.err.println("FATAL: failed to load config");
            System.exit(1);
        }
        System.out.println("Config loaded OK");

        // 获取默认仓库名
        String defaultRepo = (String) config.getOrDefault("default-repository", "default");

        // 创建并配置 Javalin
        Javalin app = Javalin.create()
                .start(9000);

        System.out.println("Server started on http://localhost:9000");

        // Health check
        app.get("/graph/health", ctx -> {
            ctx.json(ApiResponse.success("OK"));
        });

        // Query endpoint — reponame from path
        app.post("/graph/{reponame}/query", ctx -> {
            String reponame = ctx.pathParam("reponame");
            System.out.println("Query request: reponame=" + reponame);

            try {
                String effectiveRepo = reponame != null && !reponame.isBlank() ? reponame : defaultRepo;
                System.out.println("Using repo: " + effectiveRepo);

                GraphBackend backend = getBackend(effectiveRepo, config);
                if (backend == null) {
                    ctx.status(500).json(ApiResponse.error("BACKEND_ERROR", "Backend not available"));
                    return;
                }

                // 手动解析 JSON body 为 Map，避免 Jackson String/Object 类型推断问题
                @SuppressWarnings("unchecked")
                Map<String, Object> bodyMap = mapper.readValue(ctx.body(), Map.class);
                GraphQueryRequest request = new GraphQueryRequest();
                request.setQueryType((String) bodyMap.get("queryType"));
                request.setSource(bodyMap.get("source"));
                request.setTarget(bodyMap.get("target"));
                request.setMode((String) bodyMap.get("mode"));
                request.setEdgeLabels(bodyMap.get("edgeLabels") instanceof java.util.List
                    ? ((java.util.List<String>) bodyMap.get("edgeLabels")).toArray(new String[0]) : null);
                Object maxHops = bodyMap.get("maxHops");
                request.setMaxHops(maxHops instanceof Number ? ((Number) maxHops).intValue() : null);
                Object minHops = bodyMap.get("minHops");
                request.setMinHops(minHops instanceof Number ? ((Number) minHops).intValue() : 1);
                request.setDirection((String) bodyMap.get("direction"));
                request.setResultScope((String) bodyMap.get("resultScope"));
                request.setPath(bodyMap.get("path") != null
                    ? mapper.convertValue(bodyMap.get("path"), com.jhk.graph.dto.request.PathElement[].class) : null);
                request.setNodes(bodyMap.get("nodes") != null
                    ? mapper.convertValue(bodyMap.get("nodes"), com.jhk.graph.dto.request.PatternVertex[].class) : null);
                request.setEdges(bodyMap.get("edges") != null
                    ? mapper.convertValue(bodyMap.get("edges"), com.jhk.graph.dto.request.PatternEdge[].class) : null);
                request.setWhere((String) bodyMap.get("where"));
                request.setSelect(bodyMap.get("select") instanceof java.util.List
                    ? ((java.util.List<String>) bodyMap.get("select")).toArray(new String[0]) : null);
                request.setTargetProperties(bodyMap.get("targetProperties") instanceof java.util.List
                    ? ((java.util.List<String>) bodyMap.get("targetProperties")).toArray(new String[0]) : null);
                Object dryRun = bodyMap.get("dryRun");
                request.setDryRun(dryRun instanceof Boolean ? (Boolean) dryRun : "true".equalsIgnoreCase(String.valueOf(dryRun)));
                Object limit = bodyMap.get("limit");
                request.setLimit(limit instanceof Number ? ((Number) limit).intValue() : 10);
                System.out.println("Request parsed: queryType=" + request.getQueryType() + ", dryRun=" + request.getDryRun());

                if (request.getQueryType() == null || request.getQueryType().isBlank()) {
                    ctx.status(400).json(ApiResponse.error("INVALID_QUERY_TYPE", "queryType is required"));
                    return;
                }

                // dryRun 模式：仅返回生成的 SPARQL，不执行查询
                if (Boolean.TRUE.equals(request.getDryRun())) {
                    System.out.println("dryRun=true, generating SPARQL only...");
                    String sparql = backend.buildSparql(request);
                    System.out.println("SPARQL generated OK");
                    ctx.json(ApiResponse.success(Map.of("sparql", sparql)));
                    return;
                }

                System.out.println("Executing query...");
                GraphQueryResponse result = backend.execute(request);
                System.out.println("Query executed OK, result size=" + (result.getPaths() != null ? result.getPaths().size() : 0));
                ctx.json(ApiResponse.success(result));

            } catch (GraphQueryException e) {
                String code = e.getCode() != null ? e.getCode() : "UNKNOWN";
                System.out.println("GraphQueryException: " + code + " - " + e.getMessage());
                e.printStackTrace();
                int status = mapCodeToStatus(code);
                ctx.status(status);
                ctx.contentType("application/json");
                ctx.result("{\"success\":false,\"error\":{\"code\":\"" + escapeJson(code) + "\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}}");
            } catch (JsonProcessingException e) {
                // JSON 格式错误 → 400 Bad Request
                System.out.println("JSON parse error: " + e.getMessage());
                ctx.status(400);
                ctx.contentType("application/json");
                ctx.result("{\"success\":false,\"error\":{\"code\":\"INVALID_JSON\",\"message\":\"Invalid request body: " + escapeJson(e.getOriginalMessage()) + "\"}}");
            } catch (IllegalArgumentException e) {
                // 参数校验错误 → 400
                System.out.println("Illegal argument: " + e.getMessage());
                ctx.status(400);
                ctx.contentType("application/json");
                ctx.result("{\"success\":false,\"error\":{\"code\":\"INVALID_ARGUMENT\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}}");
            } catch (IOException e) {
                // IO/后端连接错误 → 503
                System.out.println("IO error: " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace();
                ctx.status(503);
                ctx.contentType("application/json");
                ctx.result("{\"success\":false,\"error\":{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}}");
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                System.out.println("Unhandled exception: " + e.getClass().getName() + ": " + msg);
                e.printStackTrace();
                ctx.status(500);
                ctx.contentType("application/json");
                ctx.result("{\"success\":false,\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"" + escapeJson(msg) + "\"}}");
            } catch (Error e) {
                System.out.println("FATAL ERROR: " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace();
                ctx.status(500);
                ctx.contentType("application/json");
                ctx.result("{\"success\":false,\"error\":{\"code\":\"FATAL_ERROR\",\"message\":\"" + escapeJson(e.getClass().getName() + ": " + e.getMessage()) + "\"}}");
            }
        });

        // 全局异常处理器——捕获路由之外、try-catch 遗漏的 Throwable（含 Error）
        app.exception(Exception.class, (e, ctx) -> {
            System.out.println("=== GLOBAL EXCEPTION HANDLER ===");
            System.out.println("Type: " + e.getClass().getName());
            System.out.println("Message: " + e.getMessage());
            e.printStackTrace();
            System.out.println("Request: " + ctx.method() + " " + ctx.path());
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            String code;
            int status;
            if (e instanceof IllegalArgumentException || e instanceof JsonProcessingException) {
                code = "INVALID_ARGUMENT";
                status = 400;
            } else if (e instanceof IOException) {
                code = "SERVICE_UNAVAILABLE";
                status = 503;
            } else if (e instanceof GraphQueryException) {
                code = ((GraphQueryException) e).getCode();
                status = mapCodeToStatus(code);
                msg = e.getMessage();
            } else {
                code = "INTERNAL_ERROR";
                status = 500;
            }
            ctx.status(status);
            ctx.contentType("application/json");
            ctx.result("{\"success\":false,\"error\":{\"code\":\"" + code + "\",\"message\":\"" + escapeJson(msg) + "\"}}");
        });

        System.out.println("Handler registered OK");
        System.out.println("Waiting for requests...");
    }

    /**
     * 获取指定仓库的 backend 实例（带缓存）
     */
    @SuppressWarnings("unchecked")
    private static GraphBackend getBackend(String reponame, Map<String, Object> config) {
        if (backendCache.containsKey(reponame)) {
            return backendCache.get(reponame);
        }

        System.out.println("Creating backend for repo: " + reponame);

        Map<String, Object> repos = (Map<String, Object>) config.get("repositories");
        if (repos == null || !repos.containsKey(reponame)) {
            throw new GraphQueryException("INVALID_QUERY_TYPE", "Repository not found: " + reponame);
        }

        Map<String, Object> repoConfig = (Map<String, Object>) repos.get(reponame);
        String backendType = (String) repoConfig.getOrDefault("type", "sparql");

        GraphBackend backend;
        if ("sparql".equals(backendType)) {
            Map<String, Object> sparqlCfg = (Map<String, Object>) repoConfig.get("sparql");
            if (sparqlCfg == null) {
                throw new GraphQueryException("BACKEND_ERROR", "sparql config missing for repository: " + reponame);
            }
            String ontologyPath = (String) sparqlCfg.get("ontologyPath");
            String endpoint = (String) sparqlCfg.getOrDefault("endpoint", "");

            System.out.println("  ontologyPath: " + ontologyPath);
            System.out.println("  endpoint: " + endpoint);

            if (ontologyPath == null || ontologyPath.isBlank()) {
                throw new GraphQueryException("BACKEND_ERROR", "ontologyPath is empty for repository: " + reponame);
            }

            SparqlProperties props = new SparqlProperties();
            props.setOntologyPath(ontologyPath);
            props.setEndpoint(endpoint);

            // Read optional prefix configuration
            Object prefixCfg = sparqlCfg.get("prefixes");
            if (prefixCfg instanceof Map) {
                Map<String, Object> prefixMap = (Map<String, Object>) prefixCfg;
                Map<String, String> resolved = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, Object> e : prefixMap.entrySet()) {
                    resolved.put(e.getKey(), e.getValue() != null ? e.getValue().toString() : "");
                }
                props.setPrefixes(resolved);
            }
            // Backward compatibility: if "prefix" is set, it populates bacls/baprop defaults
            if (sparqlCfg.containsKey("prefix")) {
                props.setPrefix((String) sparqlCfg.get("prefix"));
            }
            if (sparqlCfg.containsKey("typePrefix")) {
                props.setTypePrefix((String) sparqlCfg.get("typePrefix"));
            }
            if (sparqlCfg.containsKey("propPrefix")) {
                props.setPropPrefix((String) sparqlCfg.get("propPrefix"));
            }

            System.out.println("  Prefixes: " + props.getPrefixes());
            System.out.println("  typePrefix: " + props.getTypePrefix() + ", propPrefix: " + props.getPropPrefix());

            System.out.println("  Initializing SparqlBackend...");
            backend = new SparqlBackend(props);
            System.out.println("  SparqlBackend created OK");
        } else if ("nebula".equals(backendType)) {
            Map<String, Object> nebulaCfg = (Map<String, Object>) repoConfig.get("nebula");
            if (nebulaCfg == null) {
                throw new GraphQueryException("BACKEND_ERROR", "nebula config missing for repository: " + reponame);
            }

            NebulaProperties props = new NebulaProperties();
            props.setAddress((String) nebulaCfg.get("address"));
            props.setUsername((String) nebulaCfg.getOrDefault("username", "root"));
            props.setPassword((String) nebulaCfg.getOrDefault("password", ""));
            props.setSpace((String) nebulaCfg.get("space"));

            Object poolSize = nebulaCfg.get("poolSize");
            props.setPoolSize(poolSize instanceof Number ? ((Number) poolSize).intValue() : 10);

            Object timeout = nebulaCfg.get("timeout");
            props.setTimeout(timeout instanceof Number ? ((Number) timeout).intValue() : 3000);

            System.out.println("  Initializing NebulaBackend...");
            backend = new NebulaBackend(props);
            System.out.println("  NebulaBackend created OK");
        } else {
            throw new GraphQueryException("INVALID_QUERY_TYPE", "Unsupported backend type: " + backendType);
        }

        backendCache.put(reponame, backend);
        return backend;
    }

    private static int mapCodeToStatus(String code) {
        if (code == null) return 400;
        return switch (code) {
            case "INVALID_QUERY_TYPE", "INVALID_FILTER", "INVALID_PATTERN" -> 400;
            case "VERTEX_NOT_FOUND", "EDGE_NOT_FOUND" -> 404;
            case "TIMEOUT" -> 408;
            default -> 500;
        };
    }

    /**
     * 加载配置：
     * 1. 命令行指定路径 -> 从文件系统加载
     * 2. classpath: config.yaml
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();

        // 1. 优先从命令行指定路径加载
        if (configPath != null) {
            Path p = Path.of(configPath);
            if (Files.exists(p)) {
                System.out.println("Loading config from file: " + configPath);
                try (InputStream in = new FileInputStream(p.toFile())) {
                    return yaml.load(in);
                }
            } else {
                System.err.println("Config file not found: " + configPath);
                return null;
            }
        }

        // 2. 从 classpath 加载
        System.out.println("Loading config from classpath...");
        try (InputStream in = GraphSearchApplication.class.getClassLoader().getResourceAsStream("config.yaml")) {
            if (in != null) {
                System.out.println("config.yaml found in classpath");
                return yaml.load(in);
            }
        }

        System.err.println("config.yaml not found on classpath");
        return null;
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar graph-search-api.jar [options]");
        System.err.println("Options:");
        System.err.println("  -c, --config <path>   Path to config.yaml file");
        System.err.println("Examples:");
        System.err.println("  java -jar graph-search-api.jar");
        System.err.println("  java -jar graph-search-api.jar -c /etc/graph-search/config.yaml");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
