package io.semanticmap.platform.graph;

import io.semanticmap.platform.graph.internal.SemanticDiffer;
import io.semanticmap.platform.graph.internal.Semantics;
import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GraphQueries {
    public enum View {
        BUSINESS,
        DEVELOPER,
        DATA_OWNERSHIP,
        SECURITY,
        DATA_PIPELINE,
        CONFIDENCE
    }

    public record Filters(String analyzer, String component, String framework, String sourceLayer) {}

    private final Db db;
    private final TenantContext tenant;
    private final Access access;
    private final SemanticDiffer differ;
    private static final String NODE_SELECT =
            "SELECT n.*,coalesce(rv.decision,'UNREVIEWED') AS review_status,rv.edited_title,rv.edited_description,en.title AS ai_label,en.description AS ai_subtitle FROM semantic_node n LEFT JOIN LATERAL (SELECT r.decision,r.edited_title,r.edited_description FROM review_decision r WHERE r.organization_id=n.organization_id AND r.project_id=n.project_id AND r.analysis_run_id=n.analysis_run_id AND r.node_id=n.id ORDER BY r.created_at DESC,r.id DESC LIMIT 1) rv ON true LEFT JOIN LATERAL (SELECT e.title,e.description FROM llm_enrichment e WHERE e.organization_id=n.organization_id AND e.project_id=n.project_id AND e.analysis_run_id=n.analysis_run_id AND e.node_id=n.id ORDER BY e.created_at DESC,e.id DESC LIMIT 1) en ON true ";
    private static final String PROJECTION_SELECT = NODE_SELECT.replace(
            "n.*",
            "n.id,n.stable_key,n.kind,left(n.label,300) AS label,left(n.subtitle,500) AS subtitle,n.confidence,n.support_level,n.unresolved,jsonb_array_length(n.evidence_ids) AS evidence_count,CASE WHEN octet_length(n.properties::text)<=16384 THEN n.properties - 'factAlternatives' ELSE jsonb_build_object('detailsAvailable',true,'category',n.properties->'category','sourceLayer',n.properties->'sourceLayer','factConflict',n.properties->'factConflict') END AS properties");

    public GraphQueries(Db db, TenantContext tenant, Access access, SemanticDiffer differ) {
        this.db = db;
        this.tenant = tenant;
        this.access = access;
        this.differ = differ;
    }

    public Map<String, Object> scopedNode(UUID id) {
        var existing = db.rows(NODE_SELECT + "WHERE n.id=? AND n.organization_id=?", id, tenant.orgId());
        if (!existing.isEmpty()) {
            var node = existing.getFirst();
            access.project(Semantics.uuid(node.get("projectId")));
            return node;
        }

        // Check if this ID is a synthetic return node for an endpoint in this organization
        var endpoints = db.rows(
                NODE_SELECT + "WHERE n.organization_id=? AND n.kind IN ('ENDPOINT', 'BUSINESS_SCENARIO')",
                tenant.orgId());
        for (var ep : endpoints) {
            Object epId = ep.get("id");
            if (epId != null) {
                UUID expectedReturnId = UUID.nameUUIDFromBytes((epId.toString() + ":return").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (expectedReturnId.equals(id)) {
                    access.project(Semantics.uuid(ep.get("projectId")));
                    var props = Semantics.map(ep.get("properties"));
                    String responseType = java.util.Objects.toString(props.get("responseType"), "");
                    String simpleType = responseType.contains(".") ? responseType.substring(responseType.lastIndexOf('.') + 1) : responseType;
                    if (simpleType.isBlank()) simpleType = "HTTP 200 OK";

                    var synth = new LinkedHashMap<String, Object>();
                    synth.put("id", id);
                    synth.put("stable_key", "return:" + epId);
                    synth.put("stableKey", "return:" + epId);
                    synth.put("kind", "RETURN_OUTCOME");
                    synth.put("label", "Возврат ответа клиенту");
                    synth.put("subtitle", "Завершение обработки запроса и возврат результата (" + simpleType + ")");
                    synth.put("confidence", 1.0);
                    synth.put("support_level", "EXPLICIT");
                    synth.put("supportLevel", "EXPLICIT");
                    synth.put("aiLabel", "Возврат результата клиенту");
                    synth.put("aiSubtitle", "Успешное завершение сценария (" + simpleType + ")");
                    synth.put("properties", Map.of(
                            "responseType", responseType,
                            "outcome", "SUCCESS",
                            "sourceLayer", "BACKEND",
                            "originEndpointId", epId.toString(),
                            "filePath", props.getOrDefault("filePath", "")
                    ));
                    synth.put("evidenceIds", ep.get("evidenceIds") != null ? ep.get("evidenceIds") : List.of());
                    synth.put("sourceFactIds", List.of());
                    synth.put("projectId", ep.get("projectId"));
                    synth.put("analysisRunId", ep.get("analysisRunId"));
                    synth.put("organizationId", ep.get("organizationId"));
                    synth.put("reviewStatus", "UNREVIEWED");
                    return synth;
                }
            }
        }

        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Resource not found");
    }

    public Map<String, Object> scopedEdge(UUID id) {
        var edge = db.one("SELECT * FROM semantic_edge WHERE id=? AND organization_id=?", id, tenant.orgId());
        access.project(Semantics.uuid(edge.get("projectId")));
        return edge;
    }

    public Map<String, Object> node(UUID id) {
        var raw = scopedNode(id);
        var result = new LinkedHashMap<>(nodeDto(raw));
        if ("RETURN_OUTCOME".equals(raw.get("kind"))) {
            result.put("assertions", List.of());
            result.put("facts", List.of());
            result.put("reviews", List.of());
            result.put("scenarios", List.of());
            result.put("scenarioMembers", List.of());
            String respType = Semantics.map(raw.get("properties")).getOrDefault("responseType", "ResponseEntity").toString();
            if (respType.isBlank()) respType = "HTTP 200 OK";
            result.put("enrichment", Map.of(
                    "id", UUID.randomUUID(),
                    "title", "Возврат результата клиенту",
                    "description", "Успешное завершение обработки HTTP-запроса в контроллере и передача ответа клиенту (" + respType + ").",
                    "trust_status", "CONFIRMED",
                    "claims", List.of("Формирование и сериализация HTTP-ответа", "Передача данных в сетевой канал вызывающей стороны"),
                    "created_at", java.time.Instant.now().toString()
            ));
            return result;
        }
        var assertions = db.rows(
                "SELECT id,category,normalized_condition,true_outcomes,false_outcomes,score_breakdown,model,confidence FROM assertion WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND node_id=?",
                tenant.orgId(),
                raw.get("projectId"),
                raw.get("analysisRunId"),
                id);
        result.put("assertions", assertions);
        result.put(
                "facts",
                db.rows(
                        "SELECT id,kind,stable_key,origin,confidence,payload FROM raw_fact WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id::text IN (SELECT jsonb_array_elements_text(?::jsonb)) ORDER BY id LIMIT 100",
                        tenant.orgId(),
                        raw.get("projectId"),
                        raw.get("analysisRunId"),
                        db.json(raw.get("sourceFactIds"))));
        result.put(
                "reviews",
                db.rows(
                        "SELECT id,user_id,decision,comment,edited_title,edited_description,merge_target_id,carried_from_id,created_at FROM review_decision WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND node_id=? ORDER BY created_at DESC,id DESC LIMIT 50",
                        tenant.orgId(),
                        raw.get("projectId"),
                        raw.get("analysisRunId"),
                        id));
        result.put(
                "scenarios",
                db.rows(
                        "SELECT id,model,truncated FROM business_scenario WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND node_id=?",
                        tenant.orgId(),
                        raw.get("projectId"),
                        raw.get("analysisRunId"),
                        id));
        result.put(
                "scenarioMembers",
                db.rows(
                        "SELECT m.depth, m.role, n.id, n.kind, n.label, n.subtitle, n.stable_key, n.properties FROM business_scenario_member m JOIN semantic_node n ON m.node_id = n.id WHERE m.organization_id=? AND m.project_id=? AND m.analysis_run_id=? AND m.scenario_id IN (SELECT id FROM business_scenario WHERE node_id=? OR entry_node_id=?) ORDER BY m.depth, n.kind, n.label",
                        tenant.orgId(),
                        raw.get("projectId"),
                        raw.get("analysisRunId"),
                        id,
                        id));
        var enrichment = db.rows(
                "SELECT e.id, e.title, e.description, e.category, e.claims, e.ambiguities, e.result, e.trust_status, e.created_at FROM llm_enrichment e WHERE e.organization_id=? AND e.project_id=? AND e.analysis_run_id=? AND (e.node_id=? OR e.node_id IN (SELECT node_id FROM business_scenario WHERE entry_node_id=?) OR e.node_id IN (SELECT entry_node_id FROM business_scenario WHERE node_id=?)) ORDER BY e.created_at DESC LIMIT 1",
                tenant.orgId(),
                raw.get("projectId"),
                raw.get("analysisRunId"),
                id,
                id,
                id);
        result.put("enrichment", enrichment.isEmpty() ? null : enrichment.getFirst());
        return result;
    }

    public Map<String, Object> edge(UUID id) {
        return edgeDto(scopedEdge(id));
    }

    public List<Map<String, Object>> nodeEvidence(UUID id) {
        var node = scopedNode(id);
        var list = evidence(node);
        enrichEvidenceWithEnclosingMethod(node, list);
        return list;
    }

    private void enrichEvidenceWithEnclosingMethod(Map<String, Object> node, List<Map<String, Object>> evidenceList) {
        if (evidenceList == null || evidenceList.isEmpty()) return;

        var props = Semantics.map(node.get("properties"));
        String ownerKey = java.util.Objects.toString(props.get("ownerKey"), "");
        String kind = java.util.Objects.toString(node.get("kind"), "");
        boolean isClassOrType = "CLASS".equals(kind) || "TYPE".equals(kind) || "RECORD".equals(kind)
                || "INTERFACE".equals(kind) || "SCHEMA".equals(kind) || "COMPONENT".equals(kind);

        Map<String, Object> ownerEv = null;
        if (!isClassOrType && !ownerKey.isBlank() && !ownerKey.startsWith("java:package:") && !ownerKey.startsWith("package:")) {
            var ownerRows = db.rows(
                    """
                    SELECT ev.snippet, ev.start_line, ev.end_line, ev.file_path, o.kind
                    FROM semantic_node o
                    JOIN evidence ev ON ev.id::text IN (SELECT jsonb_array_elements_text(o.evidence_ids))
                    WHERE o.organization_id = ? AND o.project_id = ? AND o.analysis_run_id = ?
                      AND o.stable_key = ?
                      AND o.kind NOT IN ('PACKAGE', 'MODULE', 'PROJECT', 'REPOSITORY')
                    LIMIT 1
                    """,
                    tenant.orgId(),
                    node.get("projectId"),
                    node.get("analysisRunId"),
                    ownerKey);
            if (!ownerRows.isEmpty()) {
                ownerEv = ownerRows.getFirst();
            }
        }

        for (var row : evidenceList) {
            String snippet = java.util.Objects.toString(row.get("snippet"), "");
            Object startLine = row.get("startLine");
            Object endLine = row.get("endLine");

            if (ownerEv != null && ownerEv.get("snippet") != null) {
                String ownerSnippet = ownerEv.get("snippet").toString();
                String declaration = extractDeclaration(java.util.Objects.toString(ownerEv.get("kind"), "METHOD"), ownerSnippet);

                row.put("enclosingDeclaration", declaration);
                row.put("enclosing_declaration", declaration);
                row.put("enclosingSnippet", ownerSnippet);
                row.put("enclosing_snippet", ownerSnippet);
                row.put("enclosingStartLine", ownerEv.get("startLine"));
                row.put("enclosing_start_line", ownerEv.get("startLine"));
                row.put("enclosingEndLine", ownerEv.get("endLine"));
                row.put("enclosing_end_line", ownerEv.get("endLine"));
                row.put("highlightStartLine", startLine);
                row.put("highlight_start_line", startLine);
                row.put("highlightEndLine", endLine);
                row.put("highlight_end_line", endLine);
            } else {
                String declaration = extractDeclaration(kind, snippet);
                row.put("enclosingDeclaration", declaration);
                row.put("enclosing_declaration", declaration);
                row.put("enclosingSnippet", snippet);
                row.put("enclosing_snippet", snippet);
                row.put("enclosingStartLine", startLine);
                row.put("enclosing_start_line", startLine);
                row.put("enclosingEndLine", endLine);
                row.put("enclosing_end_line", endLine);
                row.put("highlightStartLine", startLine);
                row.put("highlight_start_line", startLine);
                row.put("highlightEndLine", endLine);
                row.put("highlight_end_line", endLine);
            }
        }
    }

    private static String extractDeclaration(String kind, String snippet) {
        if (snippet == null || snippet.isBlank()) return "";
        if ("CLASS".equals(kind) || "TYPE".equals(kind) || "RECORD".equals(kind) || "INTERFACE".equals(kind)) {
            for (String line : snippet.lines().toList()) {
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) continue;
                if (trimmed.contains("class ") || trimmed.contains("record ") || trimmed.contains("interface ")) {
                    int brace = trimmed.indexOf('{');
                    return brace != -1 ? trimmed.substring(0, brace).trim() : trimmed;
                }
            }
            for (String line : snippet.lines().toList()) {
                String trimmed = line.trim();
                if (!trimmed.isBlank() && !trimmed.startsWith("@") && !trimmed.startsWith("package ") && !trimmed.startsWith("import ")) {
                    int brace = trimmed.indexOf('{');
                    return brace != -1 ? trimmed.substring(0, brace).trim() : trimmed;
                }
            }
        }
        int braceIdx = snippet.indexOf('{');
        return ("METHOD".equals(kind) || "ENDPOINT".equals(kind))
                ? (braceIdx != -1 ? snippet.substring(0, braceIdx).trim() : snippet.lines().findFirst().orElse("").trim())
                : snippet.lines().findFirst().orElse("").trim();
    }

    public List<Map<String, Object>> edgeEvidence(UUID id) {
        return evidence(scopedEdge(id));
    }

    private List<Map<String, Object>> evidence(Map<String, Object> subject) {
        return db.rows(
                "SELECT e.id,e.file_path,e.start_line,e.end_line,e.start_column,e.end_column,e.snippet,e.snippet_hash,e.analyzer_id,e.analyzer_version,e.image_digest,e.origin,e.verified,e.revision_id,e.analyzer_execution_id,coalesce((to_jsonb(e)->>'source_available')::boolean,true) AS source_available FROM evidence e WHERE e.organization_id=? AND e.project_id=? AND e.analysis_run_id=? AND e.id::text IN (SELECT jsonb_array_elements_text(?::jsonb)) ORDER BY e.file_path,e.start_line,e.id LIMIT 200",
                tenant.orgId(),
                subject.get("projectId"),
                subject.get("analysisRunId"),
                db.json(subject.get("evidenceIds")));
    }

    public Map<String, Object> canvas(
            UUID runId,
            View view,
            int depth,
            UUID root,
            double minConfidence,
            String search,
            boolean onlyChanged,
            int lod) {
        return canvas(
                runId, view, depth, root, minConfidence, search, onlyChanged, lod, new Filters(null, null, null, null));
    }

    public Map<String, Object> canvas(
            UUID runId,
            View view,
            int depth,
            UUID root,
            double minConfidence,
            String search,
            boolean onlyChanged,
            int lod,
            Filters filters) {
        if (depth < 0
                || depth > 5
                || lod < 0
                || lod > 3
                || !Double.isFinite(minConfidence)
                || minConfidence < 0
                || minConfidence > 1
                || search.length() > 300)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid canvas bounds or filters");
        var run = access.run(runId);
        UUID org = tenant.orgId(), project = Semantics.uuid(run.get("projectId"));
        access.project(project);
        int limit =
                switch (lod) {
                    case 0 -> 80;
                    case 1 -> 120;
                    case 2 -> 250;
                    default -> 500;
                };
        boolean hasRoot = root != null;
        String filter =
                "n.organization_id=? AND n.project_id=? AND n.analysis_run_id=? AND n.confidence>=? AND position(lower(?) in lower(n.label || ' ' || n.stable_key))>0"
                        + viewFilter(view, lod, hasRoot);
        if (onlyChanged)
            filter +=
                    " AND EXISTS (SELECT 1 FROM semantic_change c WHERE c.organization_id=n.organization_id AND c.project_id=n.project_id AND c.to_analysis_run_id=n.analysis_run_id AND c.subject_stable_key=n.stable_key)";
        var args = new ArrayList<Object>(List.of(org, project, runId, minConfidence, search));
        var executionFilters = new StringBuilder();
        if (present(filters.analyzer())) {
            executionFilters.append(" AND e.analyzer_key=?");
            args.add(filters.analyzer());
        }
        if (present(filters.component())) {
            executionFilters.append(" AND (e.component_id::text=? OR c.root_path=?)");
            args.add(filters.component());
            args.add(filters.component());
        }
        if (present(filters.framework())) {
            executionFilters.append(" AND jsonb_exists(c.frameworks,?)");
            args.add(filters.framework());
        }
        if (!executionFilters.isEmpty())
            filter +=
                    " AND EXISTS (SELECT 1 FROM raw_fact f JOIN analyzer_execution e ON e.id=f.analyzer_execution_id AND e.organization_id=f.organization_id AND e.project_id=f.project_id AND e.analysis_run_id=f.analysis_run_id JOIN technology_component c ON c.id=e.component_id AND c.organization_id=e.organization_id AND c.project_id=e.project_id AND c.analysis_run_id=e.analysis_run_id WHERE f.organization_id=n.organization_id AND f.project_id=n.project_id AND f.analysis_run_id=n.analysis_run_id AND f.id IN (SELECT value::uuid FROM jsonb_array_elements_text(n.source_fact_ids))"
                            + executionFilters + ")";
        if (present(filters.sourceLayer())) {
            filter += " AND n.properties->>'sourceLayer'=?";
            args.add(filters.sourceLayer());
        }
        boolean bounded = false;
        boolean isEndpointOrScenario = false;
        if (hasRoot) {
            var rootNode = scopedNode(root);
            if (!runId.equals(Semantics.uuid(rootNode.get("analysisRunId"))))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Root is not in this analysis");
            var selected = new LinkedHashSet<String>();
            selected.add(root.toString());

            String rootKind =
                    rootNode.get("kind") != null ? rootNode.get("kind").toString() : "";
            isEndpointOrScenario = "BUSINESS_SCENARIO".equals(rootKind) || "ENDPOINT".equals(rootKind);
            if (isEndpointOrScenario) {
                int scenarioDepth = depth <= 1 ? 4 : depth == 2 ? 5 : Math.min(depth * 2 + 1, 8);
                var scenarioMembers = db.rows(
                        "SELECT node_id FROM business_scenario_member WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND scenario_id IN (SELECT id FROM business_scenario WHERE node_id=? OR entry_node_id=?) AND depth <= ?",
                        org,
                        project,
                        runId,
                        root,
                        root,
                        scenarioDepth);
                for (var sm : scenarioMembers) {
                    Object nid = sm.get("nodeId") != null ? sm.get("nodeId") : sm.get("node_id");
                    if (nid != null) selected.add(nid.toString());
                }
            }

            // Only perform BFS graph traversal if this is not an endpoint with precomputed scenario members
            boolean needTraversal = !isEndpointOrScenario || selected.size() <= 1;
            if (needTraversal) {
                List<String> frontier = new ArrayList<>(selected);
                String noiseFilter = view == View.DEVELOPER
                        ? ""
                        : " AND target_n.kind NOT IN ('LITERAL', 'PARAMETER', 'IMPORT', 'ASSIGNMENT', 'CONSTANT', 'ANNOTATION', 'TECHNICAL_GUARD') ";

                for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
                    String traversalSql = "SELECT DISTINCT (CASE WHEN e.source_id IN (SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb)) THEN e.target_id ELSE e.source_id END) AS id FROM semantic_edge e "
                            + "JOIN semantic_node target_n ON target_n.id = (CASE WHEN e.source_id IN (SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb)) THEN e.target_id ELSE e.source_id END) "
                            + "WHERE e.organization_id=? AND e.project_id=? AND e.analysis_run_id=? AND e.kind != 'PART_OF_SCENARIO' "
                            + noiseFilter
                            + "AND (e.source_id IN (SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb)) OR e.target_id IN (SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb))) ORDER BY id LIMIT ?";
                    var adjacent = db.rows(
                            traversalSql,
                            db.json(frontier),
                            db.json(frontier),
                            org,
                            project,
                            runId,
                            db.json(frontier),
                            db.json(frontier),
                            limit + 1);
                    var next = new ArrayList<String>();
                    for (var row : adjacent) {
                        String id = row.get("id").toString();
                        if (selected.contains(id)) continue;
                        if (selected.size() >= limit) {
                            bounded = true;
                            continue;
                        }
                        selected.add(id);
                        next.add(id);
                    }
                    frontier = next;
                }
            }
            filter += " AND n.id IN (SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb))";
            args.add(db.json(selected));
            if (isEndpointOrScenario) {
                if ("ENDPOINT".equals(rootKind)) {
                    filter += " AND (n.kind != 'ENDPOINT' OR n.id = ?) AND n.kind != 'BUSINESS_SCENARIO'";
                    args.add(root);
                } else {
                    filter += " AND (n.kind != 'ENDPOINT' OR n.id IN (SELECT entry_node_id FROM business_scenario WHERE id=? OR node_id=?))";
                    args.add(root);
                    args.add(root);
                }
            }
        }
        long total = ((Number) db.one("SELECT count(*) AS total FROM semantic_node n WHERE " + filter, args.toArray())
                        .get("total"))
                .longValue();
        var nodeArgs = new ArrayList<>(args);
        nodeArgs.add(limit + 1);
        String orderBy = hasRoot
                ? "CASE WHEN n.kind IN ('ENDPOINT','SCHEDULED_JOB','MESSAGE_CONSUMER','COMMAND_ENTRY_POINT','UI_ACTION') THEN 0 WHEN n.kind='BUSINESS_SCENARIO' THEN 1 WHEN n.kind='VALIDATION_RULE' THEN 2 WHEN n.kind='AUTHORIZATION_RULE' THEN 3 WHEN n.kind='SECURITY_RULE' THEN 4 WHEN n.kind='BUSINESS_RULE' THEN 5 WHEN n.kind='TRANSACTION' THEN 6 WHEN n.kind='DATABASE_WRITE' THEN 7 WHEN n.kind='DATABASE_READ' THEN 8 WHEN n.kind='EXCEPTION' THEN 9 ELSE 10 END, n.stable_key"
                : "CASE WHEN n.kind='COMPONENT' THEN 0 WHEN n.kind='MODULE' THEN 1 WHEN n.kind IN ('BUSINESS_SCENARIO','ENDPOINT','SCHEDULED_JOB','MESSAGE_CONSUMER','COMMAND_ENTRY_POINT','UI_ACTION') THEN 2 ELSE 3 END, CASE WHEN n.kind='BUSINESS_SCENARIO' THEN substring(n.stable_key from 10) WHEN n.kind IN ('ENDPOINT','SCHEDULED_JOB','MESSAGE_CONSUMER','COMMAND_ENTRY_POINT','UI_ACTION') THEN n.stable_key WHEN n.kind IN ('UNKNOWN_RULE','BUSINESS_RULE','VALIDATION_RULE','AUTHORIZATION_RULE','SECURITY_RULE','DATA_RULE','DATABASE_READ','DATABASE_WRITE','EXTERNAL_CALL','EVENT_PUBLICATION','MESSAGE_PUBLICATION','RETURN_OUTCOME','EXCEPTION','DTO_FIELD','CALL') THEN coalesce(nullif(n.properties->>'ownerKey',''),n.stable_key) ELSE n.stable_key END, CASE WHEN n.kind='BUSINESS_SCENARIO' THEN 0 WHEN n.kind IN ('ENDPOINT','SCHEDULED_JOB','MESSAGE_CONSUMER','COMMAND_ENTRY_POINT','UI_ACTION') THEN 1 ELSE 2 END, n.stable_key";
        var rows = db.rows(
                PROJECTION_SELECT + "WHERE " + filter + " ORDER BY " + orderBy + " LIMIT ?", nodeArgs.toArray());
        boolean truncated = bounded || rows.size() > limit || total > limit;
        if (rows.size() > limit) rows = rows.subList(0, limit);
        var ids = rows.stream().map(n -> n.get("id").toString()).toList();
        Map<String, Object> returnNode = null;
        if (isEndpointOrScenario && hasRoot && !ids.isEmpty()) {
            UUID returnUuid = UUID.nameUUIDFromBytes((root.toString() + ":return").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var rootNode = scopedNode(root);
            var rootProps = Semantics.map(rootNode.get("properties"));
            String responseType = java.util.Objects.toString(rootProps.get("responseType"), "");
            String simpleType = responseType.contains(".") ? responseType.substring(responseType.lastIndexOf('.') + 1) : responseType;
            if (simpleType.isBlank()) simpleType = "HTTP 200 OK";

            returnNode = new LinkedHashMap<>();
            returnNode.put("id", returnUuid);
            returnNode.put("stableKey", "return:" + root);
            returnNode.put("kind", "RETURN_OUTCOME");
            returnNode.put("label", "Возврат ответа клиенту");
            returnNode.put("subtitle", "Завершение обработки запроса и возврат результата (" + simpleType + ")");
            returnNode.put("confidence", 1.0);
            returnNode.put("supportLevel", "EXPLICIT");
            returnNode.put("properties", Map.of("responseType", responseType, "outcome", "SUCCESS", "sourceLayer", "BACKEND"));
            returnNode.put("evidenceCount", 1);
            returnNode.put("reviewStatus", "UNREVIEWED");
            returnNode.put("badges", List.of("RETURN", "BACKEND"));
            returnNode.put("aiLabel", "Возврат результата клиенту");
            returnNode.put("aiSubtitle", "Успешное завершение сценария (" + simpleType + ")");

            var mutableRows = new ArrayList<>(rows);
            mutableRows.add(returnNode);
            rows = mutableRows;
        }
        var edges = ids.isEmpty()
                ? List.<Map<String, Object>>of()
                : db.rows(
                        "SELECT id,source_id,target_id,kind,left(label,300) AS label,confidence,jsonb_array_length(evidence_ids) AS evidence_count FROM semantic_edge WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND kind != 'PART_OF_SCENARIO' AND source_id IN (SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb)) AND target_id IN (SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb)) ORDER BY id LIMIT 2001",
                        org,
                        project,
                        runId,
                        db.json(ids),
                        db.json(ids));
        if (edges.size() > 2000) {
            truncated = true;
            edges = edges.subList(0, 2000);
        }
        if (hasRoot && view != View.DEVELOPER && !ids.isEmpty()) {
            if (isEndpointOrScenario) {
                edges = buildScenarioPipelineEdges(org, project, runId, root, ids, edges, returnNode);
            } else {
                var lifted = db.rows(
                        "WITH RECURSIVE flow(source_id, current_id, target_id, hops, kind, label) AS ( "
                                + "SELECT e.source_id, e.target_id, e.target_id, 1, e.kind, e.label "
                                + "FROM semantic_edge e "
                                + "WHERE e.organization_id=? AND e.project_id=? AND e.analysis_run_id=? "
                                + "AND e.source_id::text IN (SELECT jsonb_array_elements_text(?::jsonb)) "
                                + "AND e.target_id::text NOT IN (SELECT jsonb_array_elements_text(?::jsonb)) "
                                + "AND e.kind IN ('ENTRY_TO', 'CALLS', 'CONTAINS', 'GUARDED_BY', 'AUTHORIZED_BY', 'VALIDATES', 'READS', 'WRITES', 'THROWS', 'RETURNS') "
                                + "UNION ALL "
                                + "SELECT f.source_id, e.target_id, e.target_id, f.hops + 1, "
                                + "coalesce(nullif(e.kind, 'CALLS'), f.kind), "
                                + "coalesce(nullif(e.label, ''), f.label) "
                                + "FROM flow f "
                                + "JOIN semantic_edge e ON e.organization_id=? AND e.project_id=? AND e.analysis_run_id=? AND e.source_id = f.current_id "
                                + "WHERE f.hops < 4 "
                                + "AND f.current_id::text NOT IN (SELECT jsonb_array_elements_text(?::jsonb)) "
                                + "AND e.kind IN ('CALLS', 'CONTAINS', 'GUARDED_BY', 'AUTHORIZED_BY', 'VALIDATES', 'READS', 'WRITES', 'THROWS', 'RETURNS') "
                                + ") "
                                + "SELECT DISTINCT ON (sn.id, tn.id) "
                                + "(sn.id::text || ':' || tn.id::text) AS id, "
                                + "sn.id AS source_id, "
                                + "tn.id AS target_id, "
                                + "f.kind AS kind, "
                                + "coalesce(nullif(f.label, ''), f.kind) AS label, "
                                + "1.0 AS confidence, "
                                + "1 AS evidence_count "
                                + "FROM flow f "
                                + "JOIN semantic_node sn ON sn.id = f.source_id "
                                + "JOIN semantic_node tn ON tn.id = f.target_id "
                                + "WHERE f.target_id::text IN (SELECT jsonb_array_elements_text(?::jsonb)) "
                                + "AND sn.id != tn.id "
                                + "ORDER BY sn.id, tn.id, CASE WHEN f.kind = 'CONTAINS' THEN 1 ELSE 0 END "
                                + "LIMIT 200",
                        org,
                        project,
                        runId,
                        db.json(ids),
                        db.json(ids),
                        org,
                        project,
                        runId,
                        db.json(ids),
                        db.json(ids));
                var combined = new ArrayList<>(edges);
                var existingPairs = new java.util.HashSet<String>();
                for (var e : edges) {
                    Object src = e.get("sourceId") != null ? e.get("sourceId") : e.get("source_id");
                    Object tgt = e.get("targetId") != null ? e.get("targetId") : e.get("target_id");
                    existingPairs.add(src + "->" + tgt);
                }
                for (var l : lifted) {
                    Object src = l.get("sourceId") != null ? l.get("sourceId") : l.get("source_id");
                    Object tgt = l.get("targetId") != null ? l.get("targetId") : l.get("target_id");
                    String pair = src + "->" + tgt;
                    if (!existingPairs.contains(pair)) {
                        combined.add(l);
                        existingPairs.add(pair);
                    }
                }
                edges = combined;
            }
        }
        return Map.of(
                "nodes",
                rows.stream().map(n -> nodeDto(n, view)).toList(),
                "edges",
                edges.stream().map(GraphQueries::edgeDto).toList(),
                "truncated",
                truncated,
                "totalNodes",
                total);
    }

    private static boolean present(String value) {
        if (value != null && value.length() > 300)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filter value is too long");
        return value != null && !value.isBlank();
    }

    private String viewFilter(View view, int lod, boolean hasRoot) {
        String filter =
                switch (view) {
                    case BUSINESS -> " AND n.kind IN ('REPOSITORY','COMPONENT','MODULE','BUSINESS_CONTEXT','BUSINESS_SCENARIO','BUSINESS_RULE','VALIDATION_RULE','AUTHORIZATION_RULE','SECURITY_RULE','ENDPOINT','SCHEDULED_JOB','MESSAGE_CONSUMER','UI_ACTION','COMMAND_ENTRY_POINT','ENTITY','DATABASE_READ','DATABASE_WRITE','EVENT_PUBLICATION','MESSAGE_PUBLICATION','EXTERNAL_CALL','RETURN_OUTCOME','EXCEPTION','DATA_TRANSFORMATION','TRANSACTION','DATA_RULE','UNKNOWN_RULE')";
                    case DATA_OWNERSHIP -> " AND n.kind IN ('ENDPOINT','BUSINESS_SCENARIO','COMPONENT','MODULE','CLASS','INTERFACE','ENTITY','ENTITY_FIELD','TABLE','COLUMN','DATABASE_VIEW','DATABASE_TRIGGER','DATABASE_FUNCTION','DATABASE_READ','DATABASE_WRITE','DTO','DTO_FIELD','DATA_SOURCE','EXTERNAL_CALL')";
                    case SECURITY -> " AND (n.kind IN ('AUTHORIZATION_RULE','SECURITY_RULE','ENDPOINT','BUSINESS_SCENARIO','METHOD','FUNCTION') OR jsonb_exists(n.properties,'roles') OR jsonb_exists(n.properties,'tenantCheck'))";
                    case DATA_PIPELINE -> " AND n.kind IN ('ENDPOINT','DTO','VALIDATION_RULE','AUTHORIZATION_RULE','DATA_SOURCE','DATABASE_READ','FILTER','DATA_TRANSFORMATION','SORT','AGGREGATION','DATABASE_WRITE','RETURN_OUTCOME','BUSINESS_SCENARIO','METHOD','FUNCTION')";
                    default -> "";
                };
        if (view != View.DEVELOPER) {
            filter +=
                    " AND n.kind NOT IN ('LITERAL', 'PARAMETER', 'IMPORT', 'ASSIGNMENT', 'CONSTANT', 'ANNOTATION', 'TECHNICAL_GUARD', 'FILE', 'PACKAGE')";
            if (view == View.BUSINESS) {
                filter += " AND n.kind NOT IN ('CLASS', 'INTERFACE', 'METHOD', 'FUNCTION', 'FIELD')";
            }
        }
        if (!hasRoot) {
            if (lod == 0) filter += " AND n.kind IN ('REPOSITORY','COMPONENT','MODULE')";
            if (lod == 1)
                filter +=
                        " AND n.kind IN ('COMPONENT','MODULE','BUSINESS_SCENARIO','ENDPOINT','SCHEDULED_JOB','MESSAGE_CONSUMER','UI_ACTION','COMMAND_ENTRY_POINT')";
            if (lod == 2 && view != View.DEVELOPER && view != View.CONFIDENCE) {
                if (view == View.BUSINESS)
                    filter +=
                            " AND n.kind NOT IN ('FILE','PACKAGE','CLASS','INTERFACE','METHOD','FUNCTION','PARAMETER','CONSTANT')";
                else filter += " AND n.kind NOT IN ('FILE','PACKAGE','PARAMETER','CONSTANT')";
            }
        }
        if (view == View.BUSINESS)
            filter +=
                    " AND coalesce((SELECT r.decision FROM review_decision r WHERE r.organization_id=n.organization_id AND r.project_id=n.project_id AND r.analysis_run_id=n.analysis_run_id AND r.node_id=n.id ORDER BY r.created_at DESC,r.id DESC LIMIT 1),'UNREVIEWED') NOT IN ('REJECTED','MARKED_TECHNICAL','MERGED')";
        return filter;
    }

    public List<Map<String, Object>> controllers(UUID runId) {
        var run = access.run(runId);
        UUID org = tenant.orgId(), project = Semantics.uuid(run.get("projectId"));
        access.project(project);
        var rows = db.rows(
                "SELECT n.properties->>'ownerKey' AS owner_key, n.properties->>'filePath' AS file_path, count(DISTINCT n.id) AS endpoint_count, "
                        + "jsonb_agg(DISTINCT jsonb_build_object("
                        + "'id', n.id, "
                        + "'label', n.label, "
                        + "'method', n.properties->>'httpMethod', "
                        + "'path', n.properties->>'path', "
                        + "'methodName', substring(n.properties->>'controllerMethodKey' from '#([a-zA-Z0-9_]+)'), "
                        + "'aiTitle', coalesce(en.title, ''), "
                        + "'aiDescription', coalesce(en.description, ''), "
                        + "'aiResult', en.result, "
                        + "'hasAi', (en.id IS NOT NULL), "
                        + "'scenarioId', story.node_id, "
                        + "'stepCount', coalesce((SELECT count(*) FROM business_scenario_member member WHERE member.organization_id=n.organization_id AND member.project_id=n.project_id AND member.analysis_run_id=n.analysis_run_id AND member.scenario_id=story.id), 0), "
                        + "'sideEffectCount', coalesce((SELECT count(*) FROM business_scenario_member member JOIN semantic_node effect ON effect.id=member.node_id AND effect.organization_id=member.organization_id AND effect.project_id=member.project_id AND effect.analysis_run_id=member.analysis_run_id WHERE member.organization_id=n.organization_id AND member.project_id=n.project_id AND member.analysis_run_id=n.analysis_run_id AND member.scenario_id=story.id AND effect.kind IN ('DATABASE_READ','DATABASE_WRITE','EXTERNAL_CALL','EVENT_PUBLICATION','MESSAGE_PUBLICATION')), 0), "
                        + "'unresolvedCount', coalesce((SELECT count(*) FROM business_scenario_member member JOIN semantic_node unresolved ON unresolved.id=member.node_id AND unresolved.organization_id=member.organization_id AND unresolved.project_id=member.project_id AND unresolved.analysis_run_id=member.analysis_run_id WHERE member.organization_id=n.organization_id AND member.project_id=n.project_id AND member.analysis_run_id=n.analysis_run_id AND member.scenario_id=story.id AND unresolved.unresolved), 0), "
                        + "'pathOrderKnown', coalesce((story.model->>'pathOrderKnown')::boolean, false), "
                        + "'storyTruncated', coalesce(story.truncated, false), "
                        + "'storyStatus', CASE WHEN en.id IS NOT NULL THEN 'READY' WHEN story.id IS NOT NULL AND EXISTS (SELECT 1 FROM analysis_task task WHERE task.organization_id=n.organization_id AND task.project_id=n.project_id AND task.analysis_run_id=n.analysis_run_id AND task.task_type='ENRICH_BUSINESS_MAP' AND task.status IN ('PENDING','RUNNING')) THEN 'ANALYZING' WHEN story.id IS NOT NULL THEN 'STATIC_READY' ELSE 'DISCOVERED' END"
                        + ")) AS endpoints "
                        + "FROM semantic_node n "
                        + "LEFT JOIN business_scenario story ON story.entry_node_id = n.id AND story.organization_id = n.organization_id AND story.project_id = n.project_id AND story.analysis_run_id = n.analysis_run_id "
                        + "LEFT JOIN LATERAL (SELECT enrichment.id,enrichment.title,enrichment.description,enrichment.result FROM llm_enrichment enrichment WHERE enrichment.organization_id=n.organization_id AND enrichment.project_id=n.project_id AND enrichment.analysis_run_id=n.analysis_run_id AND enrichment.node_id IN (n.id,story.node_id) ORDER BY enrichment.created_at DESC,enrichment.id DESC LIMIT 1) en ON true "
                        + "WHERE n.organization_id=? AND n.project_id=? AND n.analysis_run_id=? AND n.kind='ENDPOINT' "
                        + "GROUP BY n.properties->>'ownerKey', n.properties->>'filePath' "
                        + "ORDER BY endpoint_count DESC, owner_key ASC",
                org,
                project,
                runId);
        var result = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            String ownerKey = row.get("ownerKey") != null ? row.get("ownerKey").toString() : "UnknownController";
            String filePath = row.get("filePath") != null ? row.get("filePath").toString() : "";
            String name = ownerKey;
            if (name.contains(".")) {
                name = name.substring(name.lastIndexOf('.') + 1);
            }
            String pkg = "";
            if (ownerKey.contains(":") && ownerKey.contains(".")) {
                String clean = ownerKey.substring(ownerKey.lastIndexOf(':') + 1);
                int lastDot = clean.lastIndexOf('.');
                if (lastDot > 0) {
                    pkg = clean.substring(0, lastDot);
                }
            }
            var item = new LinkedHashMap<String, Object>();
            item.put("controllerKey", ownerKey);
            item.put("controllerName", name);
            item.put("packageName", pkg);
            item.put("filePath", filePath);
            item.put("endpointCount", row.get("endpointCount"));
            item.put("endpoints", row.get("endpoints"));
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> neighbors(UUID id, int depth) {
        var node = scopedNode(id);
        return canvas(Semantics.uuid(node.get("analysisRunId")), View.DEVELOPER, depth, id, 0, "", false, 3);
    }

    public List<Map<String, Object>> quarantine(UUID runId, int limit, int offset) {
        var run = access.run(runId);
        access.project(Semantics.uuid(run.get("projectId")));
        return db.rows(
                "SELECT id,analyzer_execution_id,line_number,reason,raw_line,created_at FROM fact_quarantine WHERE organization_id=? AND project_id=? AND analysis_run_id=? ORDER BY created_at,id LIMIT ? OFFSET ?",
                tenant.orgId(),
                run.get("projectId"),
                runId,
                Math.clamp(limit, 1, 200),
                Math.clamp(offset, 0, 250000));
    }

    public List<Map<String, Object>> unresolved(UUID runId, int limit) {
        var run = access.run(runId);
        access.project(Semantics.uuid(run.get("projectId")));
        return db
                .rows(
                        PROJECTION_SELECT
                                + "WHERE n.organization_id=? AND n.project_id=? AND n.analysis_run_id=? AND n.unresolved ORDER BY n.stable_key LIMIT ?",
                        tenant.orgId(),
                        run.get("projectId"),
                        runId,
                        Math.clamp(limit, 1, 500))
                .stream()
                .map(GraphQueries::nodeDto)
                .toList();
    }

    @Transactional
    public List<Map<String, Object>> diff(UUID project, UUID from, UUID to) {
        access.project(project);
        for (UUID id : List.of(from, to)) {
            var run = access.run(id);
            if (!project.equals(Semantics.uuid(run.get("projectId"))))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not in project");
            if (!Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED").contains(java.util.Objects.toString(run.get("status"), "")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Only completed analyses can be compared");
        }
        differ.diff(tenant.orgId(), project, from, to);
        return db.rows(
                "SELECT id,change_type,subject_stable_key,impact_type,before_state AS before,after_state AS after,before_evidence_ids,after_evidence_ids,confidence FROM semantic_change WHERE organization_id=? AND project_id=? AND from_analysis_run_id=? AND to_analysis_run_id=? ORDER BY subject_stable_key,change_type LIMIT 10000",
                tenant.orgId(),
                project,
                from,
                to);
    }

    public List<Map<String, Object>> reviewQueue(UUID project, UUID runId, int limit) {
        access.project(project);
        if (runId == null) {
            var latest = db.rows(
                    "SELECT id FROM analysis_run WHERE organization_id=? AND project_id=? AND status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED') ORDER BY created_at DESC LIMIT 1",
                    tenant.orgId(),
                    project);
            if (latest.isEmpty()) return List.of();
            runId = Semantics.uuid(latest.getFirst().get("id"));
        }
        if (!project.equals(Semantics.uuid(access.run(runId).get("projectId"))))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return db
                .rows(
                        PROJECTION_SELECT
                                + "WHERE n.organization_id=? AND n.project_id=? AND n.analysis_run_id=? AND n.kind IN ('BUSINESS_RULE','BUSINESS_CONSTRAINT','VALIDATION_RULE','AUTHORIZATION_RULE','SECURITY_RULE','DATA_RULE','UNKNOWN_RULE','BUSINESS_SCENARIO') AND coalesce(rv.decision,'UNREVIEWED') IN ('UNREVIEWED','NEEDS_REVIEW','STALE') ORDER BY n.confidence,n.stable_key LIMIT ?",
                        tenant.orgId(),
                        project,
                        runId,
                        Math.clamp(limit, 1, 500))
                .stream()
                .map(GraphQueries::nodeDto)
                .toList();
    }

    public List<Map<String, Object>> search(UUID project, UUID runId, String query, int limit) {
        access.project(project);
        if (query.length() > 300) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search is too long");
        if (runId == null) {
            var runs = db.rows(
                    "SELECT id FROM analysis_run WHERE organization_id=? AND project_id=? ORDER BY created_at DESC LIMIT 1",
                    tenant.orgId(),
                    project);
            if (runs.isEmpty()) return List.of();
            runId = Semantics.uuid(runs.getFirst().get("id"));
        }
        if (!project.equals(Semantics.uuid(access.run(runId).get("projectId"))))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return db
                .rows(
                        PROJECTION_SELECT
                                + "WHERE n.organization_id=? AND n.project_id=? AND n.analysis_run_id=? AND position(lower(?) in lower(n.label || ' ' || n.stable_key))>0 ORDER BY n.stable_key LIMIT ?",
                        tenant.orgId(),
                        project,
                        runId,
                        query,
                        Math.clamp(limit, 1, 200))
                .stream()
                .map(GraphQueries::nodeDto)
                .toList();
    }

    public static Map<String, Object> nodeDto(Map<String, Object> node) {
        return nodeDto(node, View.DEVELOPER);
    }

    public static Map<String, Object> nodeDto(Map<String, Object> node, View view) {
        var dto = new LinkedHashMap<String, Object>();
        for (String key :
                List.of("id", "stableKey", "kind", "label", "subtitle", "confidence", "supportLevel", "properties"))
            dto.put(key, node.get(key));
        String review = java.util.Objects.toString(node.get("reviewStatus"), "UNREVIEWED");
        if (review.equals("EDITED")) {
            if (node.get("editedTitle") != null) dto.put("label", node.get("editedTitle"));
            if (node.get("editedDescription") != null) dto.put("subtitle", node.get("editedDescription"));
        }
        if (node.get("aiLabel") != null) {
            dto.put("aiLabel", node.get("aiLabel"));
            dto.put("aiSubtitle", node.get("aiSubtitle"));
        }

        dto.put("originalLabel", node.get("label"));
        dto.put("originalSubtitle", node.get("subtitle"));

        if (view == View.BUSINESS) {
            applyBusinessHumanizer(dto, node);
        }

        dto.put("reviewStatus", review);
        dto.put(
                "evidenceCount",
                node.getOrDefault(
                        "evidenceCount", Semantics.list(node.get("evidenceIds")).size()));
        var badges = new ArrayList<String>();
        if (Boolean.TRUE.equals(node.get("unresolved"))) badges.add("UNRESOLVED");
        if (review.equals("STALE")) badges.add("REVIEW_STALE");
        if (Boolean.TRUE.equals(Semantics.map(node.get("properties")).get("factConflict"))) badges.add("FACT_CONFLICT");
        if (dto.get("aiLabel") != null) badges.add("AI_ENRICHED");
        badges.addAll(Semantics.strings(Semantics.map(node.get("properties")).get("origins")));
        Object layer = Semantics.map(node.get("properties")).get("sourceLayer");
        if (layer != null) badges.add(layer.toString());
        dto.put("badges", badges);
        return dto;
    }

    private static void applyBusinessHumanizer(Map<String, Object> dto, Map<String, Object> node) {
        String kind = java.util.Objects.toString(node.get("kind"), "");
        String rawLabel = java.util.Objects.toString(node.get("label"), "");
        String rawSubtitle = java.util.Objects.toString(node.get("subtitle"), "");

        if (node.get("aiLabel") != null && !node.get("aiLabel").toString().isBlank()) {
            dto.put("aiLabel", node.get("aiLabel"));
            dto.put("aiSubtitle", node.get("aiSubtitle"));
            dto.put("label", node.get("aiLabel"));
            if (node.get("aiSubtitle") != null) {
                dto.put("subtitle", node.get("aiSubtitle"));
            }
            return;
        }

        String hLabel = null;
        String hSubtitle = null;

        String ownerKey = "";
        if (node.get("properties") instanceof Map<?, ?> p) {
            ownerKey = java.util.Objects.toString(p.get("ownerKey"), "");
        }
        String ownerLower = ownerKey.toLowerCase();
        String lower = rawLabel.toLowerCase();

        if ("RETURN_OUTCOME".equals(kind)) {
            hLabel = "Возврат результата клиенту";
            hSubtitle = rawSubtitle.isBlank() ? "Успешное завершение обработки (HTTP 200 OK)" : rawSubtitle;
        } else if ("TRANSACTION".equals(kind) || rawLabel.contains("Transactional")) {
            hLabel = "Транзакционный контекст";
            hSubtitle = "Выполнение операций в рамках атомарной транзакции БД";
        } else if (ownerLower.contains("normalize") || lower.contains("normalize")) {
            hLabel = "Нормализация поискового запроса";
            hSubtitle = "Очистка пробелов и приведение поискового фильтра к нижнему регистру (normalize)";
        } else if (ownerLower.contains("resolvezonegeometry") || lower.contains("resolvezonegeometry")) {
            if ("DATABASE_READ".equals(kind) || rawLabel.contains("findById")) {
                hLabel = "Запрос геометрии зоны из БД";
                hSubtitle = "Получение полигона и параметров зоны по идентификатору (zoneRepository.findById)";
            } else if ("EXCEPTION".equals(kind) || rawLabel.contains("NoCameraPermissionException")) {
                hLabel = "Ошибка: Отказ в доступе к зоне (403)";
                hSubtitle = "Выброс NoCameraPermissionException при отсутствии прав на просмотр зоны";
            } else {
                hLabel = "Проверка прав доступа к гео-зоне";
                hSubtitle = "Контроль наличия у пользователя прав VIEW на запрашиваемую гео-зону";
            }
        } else if (rawLabel.contains("findAccessibleCamerasForMap")) {
            hLabel = "Поиск доступных камер на карте";
            hSubtitle = "Выборка камер в границах видимой области (bbox) и гео-зоны с учетом прав пользователя";
        } else if ("EXCEPTION".equals(kind) || rawLabel.startsWith("throw ")) {
            if (lower.contains("only for hls") || lower.contains("sourcetype")) {
                hLabel = "Ошибка: Неподдерживаемый формат потока";
                hSubtitle = "Камера не поддерживает воспроизведение через протокол HLS";
            } else if (lower.contains("resource path")) {
                hLabel = "Ошибка: Не указан путь к ресурсу";
                hSubtitle = "Путь к запрашиваемому медиа-ресурсу является обязательным";
            } else if (rawLabel.contains("NoCameraPermissionException") || lower.contains("permission")) {
                hLabel = "Ошибка: Отказ в доступе (403)";
                hSubtitle = "У пользователя нет прав на просмотр данной камеры";
            } else if (rawLabel.contains("CameraPlaybackException")) {
                hLabel = "Ошибка: Сбой воспроизведения потока";
                hSubtitle = "Не удалось подключиться к медиа-серверу камеры";
            } else if (rawLabel.contains("NotFound") || lower.contains("not found")) {
                hLabel = "Ошибка: Запись не найдена (404)";
                hSubtitle = "Запрашиваемый ресурс отсутствует в системе";
            } else {
                int q1 = rawLabel.indexOf('"');
                int q2 = rawLabel.lastIndexOf('"');
                if (q1 >= 0 && q2 > q1) {
                    hLabel = "Ошибка: " + rawLabel.substring(q1 + 1, Math.min(q2, q1 + 45));
                    hSubtitle = rawLabel.substring(q1 + 1, q2);
                } else {
                    hLabel = "Исключение при обработке запроса";
                    hSubtitle = rawLabel;
                }
            }
        } else if (rawLabel.startsWith("if ")
                || "UNKNOWN_RULE".equals(kind)
                || "VALIDATION_RULE".equals(kind)
                || "BUSINESS_RULE".equals(kind)
                || "SECURITY_RULE".equals(kind)
                || "AUTHORIZATION_RULE".equals(kind)) {
            if (lower.contains("authentication") || lower.contains("jwtauthenticationtoken") || lower.contains("principal") || lower.contains("securitycontext")) {
                if (lower.contains("instanceof") || lower.contains("jwt")) {
                    hLabel = "Извлечение токена авторизации (JWT)";
                    hSubtitle = "Получение идентификатора пользователя из токена доступа";
                } else {
                    hLabel = "Проверка аутентификации пользователя";
                    hSubtitle = "Убедиться, что запрос содержит действующий токен и пользователь авторизован";
                }
            } else if (lower.contains("haspermission") || lower.contains("permission") || lower.contains("authority")) {
                hLabel = "Проверка прав доступа к ресурсу";
                hSubtitle = "Контроль полномочий пользователя или организации на выполнение действия";
            } else if (lower.contains("sourcetype") || lower.contains("hls")) {
                hLabel = "Проверка формата видеопотока";
                hSubtitle = "Трансляция поддерживается только для камер с типом HLS";
            } else if (lower.contains("hastext") || lower.contains("isempty") || lower.contains("isblank")) {
                if (lower.contains("resourcepath")) {
                    hLabel = "Проверка пути запрашиваемого ресурса";
                    hSubtitle = "Путь к медиа-файлу или плейлисту должен быть передан";
                } else if (lower.contains("querystring")) {
                    hLabel = "Проверка параметров URL-запроса";
                    hSubtitle = "Передача параметров запроса к вышестоящему источнику";
                } else {
                    hLabel = "Проверка обязательных параметров";
                    hSubtitle = "Контроль заполненности и корректности входных данных";
                }
            } else if (lower.contains("ispublic") || lower.contains("public")) {
                hLabel = "Проверка публичного доступа";
                hSubtitle = "Разрешить публичный просмотр без дополнительных ограничений";
            } else if (lower.contains("userid") || lower.contains("equals")) {
                hLabel = "Проверка владельца камеры";
                hSubtitle = "Контроль принадлежности камеры текущему пользователю";
            } else if (lower.contains("organization") || lower.contains("org") || lower.contains("canseeorg")) {
                hLabel = "Проверка доступа организации";
                hSubtitle = "Изоляция доступа в рамках организации (мультитенантность)";
            } else if (lower.contains("statuscode") || lower.contains("200") || lower.contains("300")) {
                hLabel = "Проверка ответа медиа-сервера";
                hSubtitle = "Контроль успешности получения потока от вышестоящего источника (HTTP 2xx)";
            } else if (lower.contains("isplaylist") || lower.contains("playlist") || lower.contains("upstreamuri")) {
                hLabel = "Определение типа медиа-ресурса";
                hSubtitle = "Маршрутизация для индексного плейлиста (.m3u8) или сегментов видео (.ts)";
            } else {
                hLabel = "Бизнес-проверка условия";
                hSubtitle = rawLabel.startsWith("if ") ? rawLabel.substring(3).trim() : rawLabel;
            }
        } else if ("DATABASE_READ".equals(kind) || rawLabel.startsWith("find") || rawLabel.startsWith("get") || rawLabel.startsWith("load")) {
            if (rawLabel.contains("findByCameraIdAndDeletedAtIsNull") || rawLabel.contains("Camera")) {
                hLabel = "Поиск активной камеры в БД";
                hSubtitle = "Загрузка метаданных камеры по ID с фильтрацией удаленных записей";
            } else {
                hLabel = "Запрос данных из базы данных";
                hSubtitle = "Извлечение записей сущности по заданным критериям";
            }
        } else if ("DATABASE_WRITE".equals(kind) || rawLabel.startsWith("save") || rawLabel.startsWith("delete") || rawLabel.startsWith("update")) {
            hLabel = "Сохранение изменений в БД";
            hSubtitle = "Запись и фиксация обновленных данных в хранилище";
        }

        if (hLabel != null) {
            dto.put("aiLabel", hLabel);
            dto.put("aiSubtitle", hSubtitle != null ? hSubtitle : "");
            dto.put("label", hLabel);
            dto.put("subtitle", hSubtitle != null ? hSubtitle : "");
        }
    }

    private List<Map<String, Object>> buildScenarioPipelineEdges(
            UUID org, UUID project, UUID runId, UUID root, List<String> ids, List<Map<String, Object>> existingEdges, Map<String, Object> returnNode) {
        if (ids.isEmpty()) return List.of();

        var nodeDetails = db.rows(
                "SELECT n.id, n.kind, n.label, n.stable_key, "
                        + "coalesce(ev.file_path, n.properties->>'filePath', '') AS file_path, "
                        + "coalesce(ev.start_line, (n.properties->>'startLine')::int, 999999) AS start_line, "
                        + "coalesce(owner_call_ev.start_line, call_ev.start_line, coalesce(ev.start_line, (n.properties->>'startLine')::int, 999999)) AS effective_order, "
                        + "CASE "
                        + "  WHEN n.kind IN ('ENDPOINT', 'BUSINESS_SCENARIO') THEN 0 "
                        + "  WHEN coalesce(ev.file_path, n.properties->>'filePath', '') ILIKE '%security%' OR n.kind IN ('SECURITY_RULE', 'AUTHORIZATION_RULE') THEN 1 "
                        + "  WHEN coalesce(ev.file_path, n.properties->>'filePath', '') ILIKE '%controller%' OR coalesce(ev.file_path, n.properties->>'filePath', '') ILIKE '%web%' THEN 2 "
                        + "  WHEN n.kind = 'TRANSACTION' THEN 3 "
                        + "  WHEN coalesce(ev.file_path, n.properties->>'filePath', '') ILIKE '%service%' THEN 4 "
                        + "  WHEN coalesce(ev.file_path, n.properties->>'filePath', '') ILIKE '%repository%' THEN 5 "
                        + "  WHEN n.kind = 'TABLE' THEN 6 "
                        + "  ELSE 7 "
                        + "END AS layer_order "
                        + "FROM semantic_node n "
                        + "LEFT JOIN LATERAL ( "
                        + "  SELECT e.file_path, e.start_line "
                        + "  FROM evidence e "
                        + "  WHERE e.organization_id = n.organization_id "
                        + "    AND e.project_id = n.project_id "
                        + "    AND e.analysis_run_id = n.analysis_run_id "
                        + "    AND n.source_fact_ids IS NOT NULL "
                        + "    AND jsonb_typeof(n.source_fact_ids) = 'array' "
                        + "    AND e.raw_fact_id IN (SELECT value::uuid FROM jsonb_array_elements_text(n.source_fact_ids)) "
                        + "  ORDER BY e.start_line ASC LIMIT 1 "
                        + ") ev ON true "
                        + "LEFT JOIN LATERAL ( "
                        + "  SELECT ev_call.start_line "
                        + "  FROM semantic_edge e "
                        + "  JOIN evidence ev_call ON ev_call.id IN (SELECT value::uuid FROM jsonb_array_elements_text(e.evidence_ids)) "
                        + "  WHERE e.organization_id = n.organization_id "
                        + "    AND e.project_id = n.project_id "
                        + "    AND e.analysis_run_id = n.analysis_run_id "
                        + "    AND e.target_id = n.id "
                        + "    AND e.source_id IN (SELECT node_id FROM business_scenario_member WHERE scenario_id IN (SELECT id FROM business_scenario WHERE node_id = ? OR entry_node_id = ?)) "
                        + "    AND e.kind IN ('CALLS', 'READS', 'WRITES') "
                        + "  ORDER BY ev_call.start_line ASC LIMIT 1 "
                        + ") call_ev ON true "
                        + "LEFT JOIN LATERAL ( "
                        + "  SELECT ev_owner.start_line "
                        + "  FROM semantic_node owner_node "
                        + "  JOIN semantic_edge e ON e.target_id = owner_node.id "
                        + "                       AND e.organization_id = owner_node.organization_id "
                        + "                       AND e.project_id = owner_node.project_id "
                        + "                       AND e.analysis_run_id = owner_node.analysis_run_id "
                        + "  JOIN evidence ev_owner ON ev_owner.id IN (SELECT value::uuid FROM jsonb_array_elements_text(e.evidence_ids)) "
                        + "  WHERE owner_node.organization_id = n.organization_id "
                        + "    AND owner_node.project_id = n.project_id "
                        + "    AND owner_node.analysis_run_id = n.analysis_run_id "
                        + "    AND owner_node.stable_key = (n.properties->>'ownerKey') "
                        + "    AND e.source_id IN (SELECT node_id FROM business_scenario_member WHERE scenario_id IN (SELECT id FROM business_scenario WHERE node_id = ? OR entry_node_id = ?)) "
                        + "    AND e.kind = 'CALLS' "
                        + "  ORDER BY ev_owner.start_line ASC LIMIT 1 "
                        + ") owner_call_ev ON true "
                        + "WHERE n.organization_id=? AND n.project_id=? AND n.analysis_run_id=? "
                        + "  AND n.id IN (SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb))",
                root,
                root,
                root,
                root,
                org,
                project,
                runId,
                db.json(ids));

        record NodeMeta(String id, String kind, String label, String stableKey, String filePath, int effectiveOrder, int startLine, int layerOrder) {}

        List<NodeMeta> spine = new ArrayList<>();
        List<NodeMeta> exceptions = new ArrayList<>();
        List<NodeMeta> tables = new ArrayList<>();

        String rootIdStr = root != null ? root.toString() : "";

        for (var row : nodeDetails) {
            String id = row.get("id").toString();
            String kind = java.util.Objects.toString(row.get("kind"), "");
            String label = java.util.Objects.toString(row.get("label"), "");
            String stableKey = java.util.Objects.toString(row.get("stableKey"), "");
            String filePath = java.util.Objects.toString(row.get("filePath"), "");
            int startLine = row.get("startLine") instanceof Number num ? num.intValue() : 999999;
            int effectiveOrder = row.get("effectiveOrder") instanceof Number num ? num.intValue() : startLine;
            int layerOrder = row.get("layerOrder") instanceof Number num ? num.intValue() : 7;

            var meta = new NodeMeta(id, kind, label, stableKey, filePath, effectiveOrder, startLine, layerOrder);
            if ("EXCEPTION".equals(kind)) {
                exceptions.add(meta);
            } else if ("TABLE".equals(kind)) {
                tables.add(meta);
            } else {
                spine.add(meta);
            }
        }

        NodeMeta returnMeta = null;
        if (returnNode != null) {
            String retId = returnNode.get("id").toString();
            String retLabel = returnNode.get("label").toString();
            returnMeta = new NodeMeta(retId, "RETURN_OUTCOME", retLabel, "return:" + root, "", 9999999, 9999999, 99);
            spine.add(returnMeta);
        }

        // Sort spine chronologically: root first, then layer_order, effective_order, file_path, start_line, stable_key
        spine.sort((a, b) -> {
            if (a.id().equals(rootIdStr)) return -1;
            if (b.id().equals(rootIdStr)) return 1;
            int cmp = Integer.compare(a.layerOrder(), b.layerOrder());
            if (cmp != 0) return cmp;
            cmp = Integer.compare(a.effectiveOrder(), b.effectiveOrder());
            if (cmp != 0) return cmp;
            cmp = a.filePath().compareTo(b.filePath());
            if (cmp != 0) return cmp;
            cmp = Integer.compare(a.startLine(), b.startLine());
            if (cmp != 0) return cmp;
            return a.stableKey().compareTo(b.stableKey());
        });

        List<Map<String, Object>> pipelineEdges = new ArrayList<>();
        var existingPairs = new java.util.HashSet<String>();

        // 1. Sequential spine edges: spine[i] -> spine[i+1]
        for (int i = 0; i < spine.size() - 1; i++) {
            var src = spine.get(i);
            var tgt = spine.get(i + 1);
            String pair = src.id() + "->" + tgt.id();
            existingPairs.add(pair);

            var edge = new LinkedHashMap<String, Object>();
            edge.put("id", pair);
            edge.put("source_id", src.id());
            edge.put("target_id", tgt.id());
            edge.put("sourceId", src.id());
            edge.put("targetId", tgt.id());
            edge.put("kind", "FLOWS_TO");
            edge.put("label", "Шаг " + (i + 1));
            edge.put("confidence", 1.0);
            edge.put("evidence_count", 1);
            edge.put("evidenceCount", 1);
            pipelineEdges.add(edge);
        }

        // Return edge: returnMeta -> root (Возврат ответа в начало)
        if (returnMeta != null && !spine.isEmpty()) {
            String returnBackPair = returnMeta.id() + "->" + rootIdStr;
            if (existingPairs.add(returnBackPair)) {
                var edge = new LinkedHashMap<String, Object>();
                edge.put("id", returnBackPair);
                edge.put("source_id", returnMeta.id());
                edge.put("target_id", rootIdStr);
                edge.put("sourceId", returnMeta.id());
                edge.put("targetId", rootIdStr);
                edge.put("kind", "RETURNS");
                edge.put("label", "Возврат ответа в начало");
                edge.put("confidence", 1.0);
                edge.put("evidence_count", 1);
                edge.put("evidenceCount", 1);
                pipelineEdges.add(edge);
            }
        }

        // 2. Exception branches: Condition -> Exception
        for (var exc : exceptions) {
            NodeMeta bestGuard = null;
            int bestDist = Integer.MAX_VALUE;
            for (var s : spine) {
                if (s.filePath().equals(exc.filePath()) && s.startLine() <= exc.startLine()) {
                    int dist = exc.startLine() - s.startLine();
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestGuard = s;
                    }
                }
            }
            if (bestGuard == null && !spine.isEmpty()) {
                bestGuard = spine.getLast();
            }
            if (bestGuard != null) {
                String pair = bestGuard.id() + "->" + exc.id();
                if (existingPairs.add(pair)) {
                    var edge = new LinkedHashMap<String, Object>();
                    edge.put("id", pair);
                    edge.put("source_id", bestGuard.id());
                    edge.put("target_id", exc.id());
                    edge.put("sourceId", bestGuard.id());
                    edge.put("targetId", exc.id());
                    edge.put("kind", "THROWS");
                    edge.put("label", "Исключение");
                    edge.put("confidence", 1.0);
                    edge.put("evidence_count", 1);
                    edge.put("evidenceCount", 1);
                    pipelineEdges.add(edge);
                }
            }
        }

        // 3. Table branches: DB Read/Write -> Table
        for (var tbl : tables) {
            boolean connected = false;
            for (var e : existingEdges) {
                Object tgt = e.get("targetId") != null ? e.get("targetId") : e.get("target_id");
                if (tbl.id().equals(String.valueOf(tgt))) {
                    Object src = e.get("sourceId") != null ? e.get("sourceId") : e.get("source_id");
                    String pair = src + "->" + tgt;
                    if (existingPairs.add(pair)) {
                        pipelineEdges.add(e);
                        connected = true;
                    }
                }
            }
            if (!connected) {
                NodeMeta dbNode = null;
                for (var s : spine) {
                    if ("DATABASE_READ".equals(s.kind()) || "DATABASE_WRITE".equals(s.kind())) {
                        dbNode = s;
                        break;
                    }
                }
                if (dbNode != null) {
                    String pair = dbNode.id() + "->" + tbl.id();
                    if (existingPairs.add(pair)) {
                        var edge = new LinkedHashMap<String, Object>();
                        edge.put("id", pair);
                        edge.put("source_id", dbNode.id());
                        edge.put("target_id", tbl.id());
                        edge.put("sourceId", dbNode.id());
                        edge.put("targetId", tbl.id());
                        edge.put("kind", "READS");
                        edge.put("label", "Таблица");
                        edge.put("confidence", 1.0);
                        edge.put("evidence_count", 1);
                        edge.put("evidenceCount", 1);
                        pipelineEdges.add(edge);
                    }
                }
            }
        }

        return pipelineEdges;
    }

    private static Map<String, Object> edgeDto(Map<String, Object> edge) {
        Object src = edge.get("sourceId") != null ? edge.get("sourceId") : edge.get("source_id");
        Object tgt = edge.get("targetId") != null ? edge.get("targetId") : edge.get("target_id");
        Object lbl = edge.get("label") != null ? edge.get("label") : "";
        Object conf = edge.get("confidence") != null ? edge.get("confidence") : 1.0;
        Object kind = edge.get("kind") != null ? edge.get("kind") : "RELATED_TO";
        Object evCount = edge.get("evidenceCount") != null ? edge.get("evidenceCount") :
                         edge.get("evidence_count") != null ? edge.get("evidence_count") :
                         Semantics.list(edge.get("evidenceIds")).size();
        return Map.of(
                "id",
                String.valueOf(edge.get("id")),
                "source",
                String.valueOf(src),
                "target",
                String.valueOf(tgt),
                "kind",
                String.valueOf(kind),
                "label",
                String.valueOf(lbl),
                "confidence",
                conf,
                "evidenceCount",
                evCount);
    }
}
