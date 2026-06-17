package com.jhk.graph.config;

import com.jhk.graph.backend.GraphBackend;
import com.jhk.graph.backend.sparql.SparqlBackend;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Backend configuration loaded from config.yaml
 */
public class BackendConfig {

    public static class SparqlProperties {
        private String ontologyPath = "";
        private String endpoint = "";

        // Flexible prefix configuration
        private Map<String, String> prefixes = new java.util.LinkedHashMap<>();
        private String typePrefix = "bacls";  // prefix name used for rdf:type declarations
        private String propPrefix = "baprop"; // prefix name used for property/edge declarations

        public SparqlProperties() {
            // Sensible defaults
            prefixes.put("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
            prefixes.put("bacls", "http://www.jhk.com/finance/business-analysis/class/");
            prefixes.put("baprop", "http://www.jhk.com/finance/business-analysis/property/");
        }

        public String getOntologyPath() { return ontologyPath; }
        public void setOntologyPath(String path) { this.ontologyPath = path; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String ep) { this.endpoint = ep; }

        public Map<String, String> getPrefixes() { return prefixes; }
        public void setPrefixes(Map<String, String> prefixes) {
            this.prefixes = prefixes != null ? prefixes : new java.util.LinkedHashMap<>();
        }

        public String getTypePrefix() { return typePrefix; }
        public void setTypePrefix(String typePrefix) { this.typePrefix = typePrefix; }

        public String getPropPrefix() { return propPrefix; }
        public void setPropPrefix(String propPrefix) { this.propPrefix = propPrefix; }

        /** RDF namespace (always needed) */
        public String getRdfNs() { return "http://www.w3.org/1999/02/22-rdf-syntax-ns#"; }

        /** Backward compatibility: set base prefix, populates defaults for class/ and property/ namespaces */
        @Deprecated
        public String getPrefix() { return prefixes.getOrDefault("bacls", ""); }
        @Deprecated
        public void setPrefix(String base) {
            if (base != null && !base.isBlank()) {
                String classNs = base.endsWith("/") ? base + "class/" : base + "/class/";
                String propNs  = base.endsWith("/") ? base + "property/" : base + "/property/";
                prefixes.put("bacls", classNs);
                prefixes.put("baprop", propNs);
            }
        }
    }

    public static class NebulaProperties {
        private String address = "127.0.0.1:9669";
        private String username = "root";
        private String password = "";
        private String space = "";
        private int poolSize = 10;
        private int timeout = 3000;

        public String getAddress() { return address; }
        public void setAddress(String a) { this.address = a; }
        public String getUsername() { return username; }
        public void setUsername(String u) { this.username = u; }
        public String getPassword() { return password; }
        public void setPassword(String p) { this.password = p; }
        public String getSpace() { return space; }
        public void setSpace(String s) { this.space = s; }
        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int size) { this.poolSize = size; }
        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadConfig(String resourcePath) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = BackendConfig.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException(resourcePath + " not found on classpath");
            return yaml.load(in);
        }
    }
}