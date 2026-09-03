package io.semanticmap.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ResponseStatusException;

public record EvidenceBundle(
        UUID orgId, UUID projectId, UUID runId, UUID nodeId, String json, String hash, Map<String, JsonNode> facts) {
    public static final Set<String> FIELDS = Set.of(
            "name",
            "symbolSignature",
            "entryPointKey",
            "actors",
            "roles",
            "security",
            "authorization",
            "normalizedCondition",
            "condition",
            "resolvedConstants",
            "constants",
            "trueOutcomes",
            "falseOutcomes",
            "outcome",
            "preconditions",
            "callPath",
            "dataReads",
            "dataWrites",
            "externalEffects",
            "exceptions",
            "relatedTests",
            "status",
            "statuses",
            "httpMethod",
            "path",
            "table",
            "column");

    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((k, v) -> sorted.put(k.toString(), canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list)
            return list.stream().map(EvidenceBundle::canonical).toList();
        return value;
    }

    @Component
    public static class Loader {
        private final Db db;
        private final TenantContext tenant;
        private final Access access;
        private final ObjectMapper mapper;
        private final SecretRedactor redactor;
        private final List<String> allowedPaths;
        private final AntPathMatcher paths = new AntPathMatcher();

        public Loader(
                Db db,
                TenantContext tenant,
                Access access,
                ObjectMapper mapper,
                SecretRedactor redactor,
                @Value("${semantic.ai.allowed-paths:**}") List<String> allowedPaths) {
            this.db = db;
            this.tenant = tenant;
            this.access = access;
            this.mapper = mapper;
            this.redactor = redactor;
            this.allowedPaths = allowedPaths;
        }

        public Map<String, Object> node(UUID id) {
            var node = db.one(
                    "SELECT id,project_id,analysis_run_id,source_fact_ids,fingerprint FROM semantic_node WHERE id=? AND organization_id=?",
                    id,
                    tenant.orgId());
            access.project(uuid(node.get("projectId")));
            return node;
        }

        public EvidenceBundle load(Map<String, Object> node) {
            UUID org = tenant.orgId(),
                    project = uuid(node.get("projectId")),
                    run = uuid(node.get("analysisRunId")),
                    id = uuid(node.get("id"));
            var facts = db.rows(
                    "SELECT id,kind,payload FROM raw_fact WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id::text IN (SELECT jsonb_array_elements_text(?::jsonb)) ORDER BY id LIMIT 33",
                    org,
                    project,
                    run,
                    db.json(node.get("sourceFactIds")));
            if (facts.isEmpty() || facts.size() > 32) fail("Evidence bundle requires between one and 32 source facts");
            Map<String, JsonNode> validationFacts = new LinkedHashMap<>();
            var items = new ArrayList<Object>();
            for (var fact : facts) {
                String factId = fact.get("id").toString();
                JsonNode payload = mapper.valueToTree(fact.get("payload"));
                ObjectNode selected = mapper.createObjectNode();
                payload.path("properties").fields().forEachRemaining(e -> {
                    if (FIELDS.contains(e.getKey())) selected.set(e.getKey(), e.getValue());
                });
                JsonNode safe = redactor.redact(selected);
                if (safe.toString().length() > 12000) fail("Fact exceeds enrichment size limit");
                var evidence = db.rows(
                        "SELECT id,file_path,start_line,end_line,snippet,snippet_hash,analyzer_id,analyzer_version,origin FROM evidence WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND raw_fact_id=? AND verified ORDER BY id LIMIT 9",
                        org,
                        project,
                        run,
                        fact.get("id"));
                if (evidence.isEmpty() || evidence.size() > 8) fail("Verified evidence exceeds enrichment limits");
                for (var ev : evidence) {
                    String path = ev.get("filePath").toString().replace('\\', '/');
                    if (path.startsWith("/")
                            || path.contains("../")
                            || path.contains(":")
                            || path.matches("(?i).*(?:^|/)(?:\\.env(?:\\..*)?|[^/]+\\.(?:pem|key|p12|pfx))$")
                            || allowedPaths.stream().noneMatch(p -> paths.match(p, path)))
                        fail("Evidence path is excluded by AI policy");
                    String snippet = ev.get("snippet").toString();
                    if (snippet.length() > 4000) fail("Source snippet exceeds enrichment size limit");
                    ev.put("snippet", redactor.redact(snippet));
                }
                validationFacts.put(factId, safe);
                items.add(Map.of("factId", factId, "kind", fact.get("kind"), "properties", safe, "evidence", evidence));
            }
            String json = db.json(
                    canonical(Map.of("nodeId", id.toString(), "fingerprint", node.get("fingerprint"), "facts", items)));
            if (json.getBytes(StandardCharsets.UTF_8).length > 48000)
                fail("Evidence bundle exceeds enrichment size limit");
            return new EvidenceBundle(org, project, run, id, json, hash(json), Map.copyOf(validationFacts));
        }

        private static UUID uuid(Object value) {
            return value instanceof UUID id ? id : UUID.fromString(value.toString());
        }

        private static void fail(String message) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
        }
    }
}
