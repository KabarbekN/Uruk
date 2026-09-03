package io.semanticmap.platform.review;

import io.semanticmap.platform.graph.GraphQueries;
import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {
    public record ReviewRequest(
            @NotBlank String decision,
            @Size(max = 8000) String comment,
            @Size(max = 300) String editedTitle,
            @Size(max = 8000) String editedDescription,
            UUID mergeTargetId) {}

    private static final Set<String> DECISIONS =
            Set.of("CONFIRMED", "REJECTED", "EDITED", "MERGED", "MARKED_TECHNICAL", "NEEDS_REVIEW");
    private final GraphQueries graph;
    private final Db db;
    private final TenantContext tenant;
    private final Audit audit;
    private final Access access;

    public ReviewController(GraphQueries graph, Db db, TenantContext tenant, Audit audit, Access access) {
        this.graph = graph;
        this.db = db;
        this.tenant = tenant;
        this.audit = audit;
        this.access = access;
    }

    @PostMapping("/semantic/nodes/{id}/reviews")
    @Transactional
    public Map<String, Object> review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest request) {
        tenant.requireRole("REVIEWER", "PROJECT_ADMIN");
        var node = graph.scopedNode(id);
        UUID project = UUID.fromString(node.get("projectId").toString());
        access.requireProjectRole(project, "REVIEWER");
        if (!DECISIONS.contains(request.decision())) throw bad("Unknown review decision");
        if (request.decision().equals("EDITED")
                && (request.editedTitle() == null || request.editedTitle().isBlank())
                && (request.editedDescription() == null
                        || request.editedDescription().isBlank()))
            throw bad("An edited title or description is required");
        if (request.decision().equals("MERGED")) {
            if (request.mergeTargetId() == null || request.mergeTargetId().equals(id))
                throw bad("A different merge target is required");
            var target = graph.scopedNode(request.mergeTargetId());
            if (!node.get("projectId").equals(target.get("projectId"))
                    || !node.get("analysisRunId").equals(target.get("analysisRunId")))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Merge target not in this analysis");
            if (Set.of("MERGED", "REJECTED", "STALE").contains(target.get("reviewStatus")))
                throw bad("Merge target must be an active semantic node");
        } else if (request.mergeTargetId() != null) throw bad("Merge target is only valid for MERGED");
        // Lock the run so concurrent reviews cannot create reciprocal merge chains.
        db.one(
                "SELECT id FROM analysis_run WHERE organization_id=? AND project_id=? AND id=? FOR UPDATE",
                tenant.orgId(),
                project,
                node.get("analysisRunId"));
        if (request.decision().equals("MERGED")) {
            var target = graph.scopedNode(request.mergeTargetId());
            if (Set.of("MERGED", "REJECTED", "STALE").contains(target.get("reviewStatus")))
                throw bad("Merge target must be an active semantic node");
            if (!db.rows(
                            "SELECT r.id FROM review_decision r WHERE r.organization_id=? AND r.project_id=? AND r.analysis_run_id=? AND r.merge_target_id=? AND r.decision='MERGED' AND r.id=(SELECT latest.id FROM review_decision latest WHERE latest.organization_id=r.organization_id AND latest.project_id=r.project_id AND latest.analysis_run_id=r.analysis_run_id AND latest.node_id=r.node_id ORDER BY latest.created_at DESC,latest.id DESC LIMIT 1) LIMIT 1",
                            tenant.orgId(),
                            project,
                            node.get("analysisRunId"),
                            id)
                    .isEmpty()) throw bad("This node is already a merge target");
        }
        UUID reviewId = UUID.randomUUID();
        db.update(
                "INSERT INTO review_decision(id,organization_id,project_id,analysis_run_id,node_id,user_id,decision,comment,edited_title,edited_description,merge_target_id,fingerprint) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                reviewId,
                tenant.orgId(),
                project,
                node.get("analysisRunId"),
                id,
                tenant.userId(),
                request.decision(),
                request.comment(),
                request.editedTitle(),
                request.editedDescription(),
                request.mergeTargetId(),
                node.get("fingerprint"));
        audit.record(
                tenant.orgId(),
                project,
                tenant.userId(),
                "SEMANTIC_REVIEW_CREATED",
                Map.of("reviewId", reviewId, "nodeId", id, "decision", request.decision()));
        return Map.of("id", reviewId, "decision", request.decision(), "node", graph.node(id));
    }

    @PostMapping("/assertions/{id}/reviews")
    @Transactional
    public Map<String, Object> assertionReview(@PathVariable UUID id, @Valid @RequestBody ReviewRequest request) {
        var assertion = db.one(
                "SELECT a.node_id FROM assertion a JOIN semantic_node n ON n.id=a.node_id AND n.organization_id=a.organization_id AND n.project_id=a.project_id AND n.analysis_run_id=a.analysis_run_id WHERE a.id=? AND a.organization_id=?",
                id,
                tenant.orgId());
        return review(UUID.fromString(assertion.get("nodeId").toString()), request);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
