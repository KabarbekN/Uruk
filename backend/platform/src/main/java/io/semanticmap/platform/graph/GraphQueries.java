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
            "SELECT n.*,coalesce(rv.decision,'UNREVIEWED') AS review_status,rv.edited_title,rv.edited_description FROM semantic_node n LEFT JOIN LATERAL (SELECT r.decision,r.edited_title,r.edited_description FROM review_decision r WHERE r.organization_id=n.organization_id AND r.project_id=n.project_id AND r.analysis_run_id=n.analysis_run_id AND r.node_id=n.id ORDER BY r.created_at DESC,r.id DESC LIMIT 1) rv ON true ";
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
        var node = db.one(NODE_SELECT + "WHERE n.id=? AND n.organization_id=?", id, tenant.orgId());
        access.project(Semantics.uuid(node.get("projectId")));
        return node;
    }

    public Map<String, Object> scopedEdge(UUID id) {
        var edge = db.one("SELECT * FROM semantic_edge WHERE id=? AND organization_id=?", id, tenant.orgId());
        access.project(Semantics.uuid(edge.get("projectId")));
        return edge;
    }

    public Map<String, Object> node(UUID id) {
        var raw = scopedNode(id);
        var result = new LinkedHashMap<>(nodeDto(raw));
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
        return result;
    }

    public Map<String, Object> edge(UUID id) {
        return edgeDto(scopedEdge(id));
    }

    public List<Map<String, Object>> nodeEvidence(UUID id) {
        return evidence(scopedNode(id));
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
        String filter =
                "n.organization_id=? AND n.project_id=? AND n.analysis_run_id=? AND n.confidence>=? AND position(lower(?) in lower(n.label || ' ' || n.stable_key))>0"
                        + viewFilter(view, lod);
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
        if (root != null) {
            var rootNode = scopedNode(root);
            if (!runId.equals(Semantics.uuid(rootNode.get("analysisRunId"))))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Root is not in this analysis");
            var selected = new LinkedHashSet<String>();
            selected.add(root.toString());
            List<String> frontier = List.of(root.toString());
            for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
                var adjacent = db.rows(
                        "SELECT DISTINCT CASE WHEN e.source_id::text IN (SELECT jsonb_array_elements_text(?::jsonb)) THEN e.target_id ELSE e.source_id END AS id FROM semantic_edge e WHERE e.organization_id=? AND e.project_id=? AND e.analysis_run_id=? AND (e.source_id::text IN (SELECT jsonb_array_elements_text(?::jsonb)) OR e.target_id::text IN (SELECT jsonb_array_elements_text(?::jsonb))) ORDER BY id LIMIT ?",
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
            filter += " AND n.id::text IN (SELECT jsonb_array_elements_text(?::jsonb))";
            args.add(db.json(selected));
        }
        long total = ((Number) db.one("SELECT count(*) AS total FROM semantic_node n WHERE " + filter, args.toArray())
                        .get("total"))
                .longValue();
        var nodeArgs = new ArrayList<>(args);
        nodeArgs.add(limit + 1);
        var rows = db.rows(
                PROJECTION_SELECT + "WHERE " + filter
                        + " ORDER BY CASE WHEN n.kind='BUSINESS_SCENARIO' THEN 0 WHEN n.kind='ENDPOINT' THEN 1 ELSE 2 END,n.stable_key LIMIT ?",
                nodeArgs.toArray());
        boolean truncated = bounded || rows.size() > limit || total > limit;
        if (rows.size() > limit) rows = rows.subList(0, limit);
        var ids = rows.stream().map(n -> n.get("id").toString()).toList();
        var edges = ids.isEmpty()
                ? List.<Map<String, Object>>of()
                : db.rows(
                        "SELECT id,source_id,target_id,kind,left(label,300) AS label,confidence,jsonb_array_length(evidence_ids) AS evidence_count FROM semantic_edge WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND source_id::text IN (SELECT jsonb_array_elements_text(?::jsonb)) AND target_id::text IN (SELECT jsonb_array_elements_text(?::jsonb)) ORDER BY id LIMIT 2001",
                        org,
                        project,
                        runId,
                        db.json(ids),
                        db.json(ids));
        if (edges.size() > 2000) {
            truncated = true;
            edges = edges.subList(0, 2000);
        }
        return Map.of(
                "nodes",
                rows.stream().map(GraphQueries::nodeDto).toList(),
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

    private String viewFilter(View view, int lod) {
        String filter =
                switch (view) {
                    case BUSINESS -> " AND n.kind IN ('REPOSITORY','COMPONENT','MODULE','BUSINESS_CONTEXT','BUSINESS_SCENARIO','BUSINESS_RULE','VALIDATION_RULE','AUTHORIZATION_RULE','SECURITY_RULE','ENDPOINT','SCHEDULED_JOB','MESSAGE_CONSUMER','UI_ACTION','COMMAND_ENTRY_POINT','ENTITY','DATABASE_READ','DATABASE_WRITE','EVENT_PUBLICATION','MESSAGE_PUBLICATION','EXTERNAL_CALL','RETURN_OUTCOME','EXCEPTION','DATA_TRANSFORMATION')";
                    case DATA_OWNERSHIP -> " AND (n.kind IN ('COMPONENT','MODULE','ENTITY','ENTITY_FIELD','TABLE','COLUMN','DATABASE_VIEW','DATABASE_TRIGGER','DATABASE_FUNCTION','DATABASE_READ','DATABASE_WRITE','DTO','DTO_FIELD','DATA_SOURCE','EXTERNAL_CALL') OR jsonb_exists(n.properties,'sourceLayer'))";
                    case SECURITY -> " AND (n.kind IN ('AUTHORIZATION_RULE','SECURITY_RULE','ENDPOINT','BUSINESS_SCENARIO') OR jsonb_exists(n.properties,'roles') OR jsonb_exists(n.properties,'tenantCheck'))";
                    case DATA_PIPELINE -> " AND n.kind IN ('ENDPOINT','DTO','VALIDATION_RULE','AUTHORIZATION_RULE','DATA_SOURCE','DATABASE_READ','FILTER','DATA_TRANSFORMATION','SORT','AGGREGATION','DATABASE_WRITE','RETURN_OUTCOME','BUSINESS_SCENARIO')";
                    default -> "";
                };
        if (lod == 0) filter += " AND n.kind IN ('REPOSITORY','COMPONENT','MODULE')";
        if (lod == 1)
            filter +=
                    " AND n.kind IN ('COMPONENT','MODULE','BUSINESS_SCENARIO','ENDPOINT','SCHEDULED_JOB','MESSAGE_CONSUMER','UI_ACTION','COMMAND_ENTRY_POINT')";
        if (lod == 2 && view != View.DEVELOPER && view != View.CONFIDENCE)
            filter +=
                    " AND n.kind NOT IN ('FILE','PACKAGE','CLASS','INTERFACE','METHOD','FUNCTION','PARAMETER','CONSTANT')";
        if (view == View.BUSINESS)
            filter +=
                    " AND coalesce((SELECT r.decision FROM review_decision r WHERE r.organization_id=n.organization_id AND r.project_id=n.project_id AND r.analysis_run_id=n.analysis_run_id AND r.node_id=n.id ORDER BY r.created_at DESC,r.id DESC LIMIT 1),'UNREVIEWED') NOT IN ('REJECTED','MARKED_TECHNICAL','MERGED')";
        return filter;
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
        var dto = new LinkedHashMap<String, Object>();
        for (String key :
                List.of("id", "stableKey", "kind", "label", "subtitle", "confidence", "supportLevel", "properties"))
            dto.put(key, node.get(key));
        String review = java.util.Objects.toString(node.get("reviewStatus"), "UNREVIEWED");
        if (review.equals("EDITED")) {
            if (node.get("editedTitle") != null) dto.put("label", node.get("editedTitle"));
            if (node.get("editedDescription") != null) dto.put("subtitle", node.get("editedDescription"));
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
        badges.addAll(Semantics.strings(Semantics.map(node.get("properties")).get("origins")));
        Object layer = Semantics.map(node.get("properties")).get("sourceLayer");
        if (layer != null) badges.add(layer.toString());
        dto.put("badges", badges);
        return dto;
    }

    private static Map<String, Object> edgeDto(Map<String, Object> edge) {
        return Map.of(
                "id",
                edge.get("id"),
                "source",
                edge.get("sourceId"),
                "target",
                edge.get("targetId"),
                "kind",
                edge.get("kind"),
                "label",
                edge.get("label"),
                "confidence",
                edge.get("confidence"),
                "evidenceCount",
                edge.getOrDefault(
                        "evidenceCount", Semantics.list(edge.get("evidenceIds")).size()));
    }
}
