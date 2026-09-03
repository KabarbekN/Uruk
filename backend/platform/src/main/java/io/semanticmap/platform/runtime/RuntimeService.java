package io.semanticmap.platform.runtime;

import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RuntimeService {
    public record Match(UUID nodeId, String status) {}

    private final Db db;
    private final TenantContext tenant;
    private final Access access;
    private final Audit audit;

    public RuntimeService(Db db, TenantContext tenant, Access access, Audit audit) {
        this.db = db;
        this.tenant = tenant;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> ingest(UUID runId, List<OtlpParser.Span> spans) {
        tenant.requireRole("DEVELOPER", "PROJECT_ADMIN");
        var run = access.run(runId);
        UUID project = uuid(run.get("projectId")), org = tenant.orgId();
        access.requireProjectRole(project, "DEVELOPER");
        db.one(
                "SELECT id FROM analysis_run WHERE id=? AND organization_id=? AND project_id=? FOR UPDATE",
                runId,
                org,
                project);
        long count = ((Number) db.one(
                                "SELECT count(*) AS total FROM runtime_span WHERE organization_id=? AND project_id=? AND analysis_run_id=?",
                                org,
                                project,
                                runId)
                        .get("total"))
                .longValue();
        if (spans.size() > OtlpParser.MAX_SPANS)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Runtime span quota exceeded");
        int inserted = 0, duplicates = 0, matched = 0;
        for (var span : spans) {
            String payloadHash = hash(db.json(canonical(db.parse(db.json(span)))));
            var existing = db.rows(
                    "SELECT payload_hash FROM runtime_span WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND trace_id=? AND span_id=?",
                    org,
                    project,
                    runId,
                    span.traceId(),
                    span.spanId());
            if (!existing.isEmpty()) {
                if (!payloadHash.equals(existing.getFirst().get("payloadHash")))
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Existing runtime span has different immutable data");
                duplicates++;
                continue;
            }
            if (count + inserted >= 100000)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Runtime span quota exceeded");
            Match match = match(org, project, runId, span.attributes());
            UUID spanRowId = UUID.randomUUID();
            int changed = db.update(
                    "INSERT INTO runtime_span(id,organization_id,project_id,analysis_run_id,trace_id,span_id,parent_span_id,name,kind,start_nanos,end_nanos,attributes,resource_attributes,scope,status,events,links,matched_node_id,match_status,payload_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?) ON CONFLICT (organization_id,project_id,analysis_run_id,trace_id,span_id) DO NOTHING",
                    spanRowId,
                    org,
                    project,
                    runId,
                    span.traceId(),
                    span.spanId(),
                    span.parentSpanId(),
                    span.name(),
                    span.kind(),
                    new BigDecimal(span.startNanos()),
                    new BigDecimal(span.endNanos()),
                    db.json(span.attributes()),
                    db.json(span.resourceAttributes()),
                    db.json(span.scope()),
                    db.json(span.status()),
                    db.json(span.events()),
                    db.json(span.links()),
                    match.nodeId(),
                    match.status(),
                    payloadHash);
            if (changed == 0) {
                var previous = db.one(
                        "SELECT payload_hash FROM runtime_span WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND trace_id=? AND span_id=?",
                        org,
                        project,
                        runId,
                        span.traceId(),
                        span.spanId());
                if (!payloadHash.equals(previous.get("payloadHash")))
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Existing runtime span has different immutable data");
                duplicates++;
                continue;
            }
            inserted++;
            if (match.nodeId() != null) {
                matched++;
                db.update(
                        "INSERT INTO runtime_observation(span_id,organization_id,project_id,analysis_run_id,node_id) VALUES (?,?,?,?,?)",
                        spanRowId,
                        org,
                        project,
                        runId,
                        match.nodeId());
            }
        }
        audit.record(
                org,
                project,
                tenant.userId(),
                "RUNTIME_TRACES_INGESTED",
                Map.of("analysisRunId", runId, "inserted", inserted, "duplicates", duplicates, "matched", matched));
        return Map.of(
                "status",
                "INGESTED",
                "acceptedSpans",
                inserted,
                "duplicateSpans",
                duplicates,
                "matchedSpans",
                matched,
                "unresolvedSpans",
                inserted - matched,
                "coverageScope",
                "UPLOADED_SPANS_ONLY");
    }

    Match match(UUID org, UUID project, UUID run, Map<String, Object> attributes) {
        Object stableKey = attributes.get("semantic.stable_key");
        if (stableKey != null) {
            if (!(stableKey instanceof String key) || key.isBlank()) return new Match(null, "UNRESOLVED");
            var nodes = db.rows(
                    "SELECT id FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND stable_key=? AND NOT unresolved LIMIT 2",
                    org,
                    project,
                    run,
                    key);
            return resolve(nodes, "MATCHED_STABLE_KEY");
        }
        Object symbol = attributes.getOrDefault("semantic.qualified_symbol", attributes.get("code.function.name"));
        if (!(symbol instanceof String qualified)
                || qualified.isBlank()
                || !(qualified.contains(".") || qualified.contains("::") || qualified.contains("#")))
            return new Match(null, "UNRESOLVED");
        var nodes = db.rows(
                "SELECT id FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND NOT unresolved AND (properties->>'symbolSignature'=? OR properties->>'qualifiedName'=? OR properties->>'qualifiedSymbol'=?) LIMIT 2",
                org,
                project,
                run,
                qualified,
                qualified,
                qualified);
        return resolve(nodes, "MATCHED_SYMBOL");
    }

    private Match resolve(List<Map<String, Object>> nodes, String matchedStatus) {
        return nodes.isEmpty()
                ? new Match(null, "UNRESOLVED")
                : nodes.size() > 1
                        ? new Match(null, "AMBIGUOUS")
                        : new Match(uuid(nodes.getFirst().get("id")), matchedStatus);
    }

    public Map<String, Object> get(UUID runId, int limit, int offset) {
        if (limit < 1 || limit > 500 || offset < 0 || offset > 100000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid runtime pagination");
        var run = access.run(runId);
        UUID project = uuid(run.get("projectId")), org = tenant.orgId();
        access.project(project);
        var spans = db.rows(
                "SELECT id,trace_id,span_id,parent_span_id,name,kind,start_nanos::text,end_nanos::text,attributes,resource_attributes,scope,status,events,links,matched_node_id,match_status,created_at FROM runtime_span WHERE organization_id=? AND project_id=? AND analysis_run_id=? ORDER BY start_nanos,id LIMIT ? OFFSET ?",
                org,
                project,
                runId,
                limit,
                offset);
        var summary = db.one(
                "SELECT count(*) AS total_spans,count(DISTINCT trace_id) AS trace_count,count(*) FILTER (WHERE matched_node_id IS NOT NULL) AS matched_spans,count(*) FILTER (WHERE matched_node_id IS NULL) AS unresolved_spans,count(DISTINCT matched_node_id) AS observed_nodes FROM runtime_span WHERE organization_id=? AND project_id=? AND analysis_run_id=?",
                org,
                project,
                runId);
        var paths = db.rows(
                "SELECT DISTINCT p.matched_node_id AS source_node_id,c.matched_node_id AS target_node_id FROM runtime_span c JOIN runtime_span p ON p.organization_id=c.organization_id AND p.project_id=c.project_id AND p.analysis_run_id=c.analysis_run_id AND p.trace_id=c.trace_id AND p.span_id=c.parent_span_id WHERE c.organization_id=? AND c.project_id=? AND c.analysis_run_id=? AND c.matched_node_id IS NOT NULL AND p.matched_node_id IS NOT NULL ORDER BY source_node_id,target_node_id LIMIT 501",
                org,
                project,
                runId);
        summary.put("coverageScope", "UPLOADED_SPANS_ONLY");
        summary.put("wholeApplicationCoverageKnown", false);
        summary.put("notObservedMeaning", "NOT_OBSERVED_IN_UPLOADED_TRACES");
        summary.put(
                "notObservedNodes",
                db.one(
                                "SELECT count(*) AS total FROM semantic_node n WHERE n.organization_id=? AND n.project_id=? AND n.analysis_run_id=? AND NOT EXISTS (SELECT 1 FROM runtime_observation o WHERE o.organization_id=n.organization_id AND o.project_id=n.project_id AND o.analysis_run_id=n.analysis_run_id AND o.node_id=n.id)",
                                org,
                                project,
                                runId)
                        .get("total"));
        return Map.of(
                "analysisRunId",
                runId,
                "spans",
                spans,
                "summary",
                summary,
                "observedPaths",
                paths.stream().limit(500).toList(),
                "pathsTruncated",
                paths.size() > 500,
                "offset",
                offset,
                "limit",
                limit,
                "hasMore",
                ((Number) summary.get("totalSpans")).longValue() > (long) offset + spans.size());
    }

    private static UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Object canonical(com.fasterxml.jackson.databind.JsonNode value) {
        if (value.isObject()) {
            var map = new TreeMap<String, Object>();
            value.fields().forEachRemaining(e -> map.put(e.getKey(), canonical(e.getValue())));
            return map;
        }
        if (value.isArray()) {
            var items = new ArrayList<>();
            value.forEach(n -> items.add(canonical(n)));
            return items;
        }
        return value;
    }
}
