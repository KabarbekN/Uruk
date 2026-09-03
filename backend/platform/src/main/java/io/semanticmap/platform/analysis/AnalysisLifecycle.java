package io.semanticmap.platform.analysis;

import io.semanticmap.contract.Protocol;
import io.semanticmap.platform.repository.RepositoryPolicy;
import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnalysisLifecycle {
    private final Db db;
    private final Access access;
    private final TenantContext tenant;
    private final Audit audit;
    private final TaskQueue queue;
    private final AnalysisEvents events;
    private final RepositoryPolicy policy;
    private final int activeLimit;
    private final int hourlyLimit;

    public AnalysisLifecycle(
            Db db,
            Access access,
            TenantContext tenant,
            Audit audit,
            TaskQueue queue,
            AnalysisEvents events,
            RepositoryPolicy policy,
            @Value("${semantic.analysis.max-active:4}") int activeLimit,
            @Value("${semantic.analysis.max-hourly:12}") int hourlyLimit) {
        this.db = db;
        this.access = access;
        this.tenant = tenant;
        this.audit = audit;
        this.queue = queue;
        this.events = events;
        this.policy = policy;
        if (activeLimit < 1 || hourlyLimit < 1) throw new IllegalArgumentException("INVALID_ANALYSIS_QUOTA");
        this.activeLimit = activeLimit;
        this.hourlyLimit = hourlyLimit;
    }

    public record Start(UUID baselineRunId, String ref) {}

    public record Run(
            UUID id,
            UUID projectId,
            String status,
            String revision,
            Object createdAt,
            Object finishedAt,
            int progress,
            String currentStage) {
        public static Run of(Map<String, Object> row) {
            return new Run(
                    (UUID) row.get("id"),
                    (UUID) row.get("projectId"),
                    (String) row.get("status"),
                    (String) row.get("revision"),
                    row.get("createdAt"),
                    row.get("finishedAt"),
                    ((Number) row.get("progress")).intValue(),
                    (String) row.get("currentStage"));
        }
    }

    public Run get(UUID run) {
        return Run.of(access.run(run));
    }

    public List<Run> list(UUID project) {
        access.project(project);
        return db
                .rows(
                        "SELECT * FROM analysis_run WHERE organization_id=? AND project_id=? ORDER BY created_at DESC LIMIT 200",
                        tenant.orgId(),
                        project)
                .stream()
                .map(Run::of)
                .toList();
    }

    @Transactional
    public Run start(UUID project, Start body, String key) throws IOException {
        tenant.requireRole("ORG_ADMIN", "PROJECT_ADMIN", "ANALYST", "DEVELOPER");
        var projectRow = access.project(project);
        access.requireProjectRole(project, "ANALYST", "DEVELOPER");
        UUID org = tenant.orgId();
        db.one("SELECT id FROM organization WHERE id=? FOR UPDATE", org);
        db.one("SELECT id FROM project WHERE organization_id=? AND id=? FOR UPDATE", org, project);
        access.project(project);
        if (key != null && !key.matches("[A-Za-z0-9_.:-]{1,200}"))
            throw new IllegalArgumentException("INVALID_IDEMPOTENCY_KEY");
        UUID baseline = body == null ? null : body.baselineRunId();
        var connections =
                db.rows("SELECT * FROM repository_connection WHERE organization_id=? AND project_id=?", org, project);
        String url = connections.isEmpty()
                ? (String) projectRow.get("repositoryPath")
                : (String) connections.getFirst().get("url");
        String credentials =
                connections.isEmpty() ? null : (String) connections.getFirst().get("credentialsReference");
        String defaultRef =
                connections.isEmpty() ? "HEAD" : (String) connections.getFirst().get("branch");
        String ref = body == null || body.ref() == null ? defaultRef : body.ref();
        policy.validate(url);
        policy.validateRef(ref);
        String config = Protocol.hash(Protocol.VERSION + "\nSAFE_STATIC\n" + url + "\n" + ref + "\n" + baseline);
        if (key != null) {
            var existing = db.rows(
                    "SELECT * FROM analysis_run WHERE organization_id=? AND project_id=? AND idempotency_key=?",
                    org,
                    project,
                    key);
            if (!existing.isEmpty()) {
                if (!config.equals(existing.getFirst().get("configHash")))
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Idempotency key was used for a different analysis request");
                return Run.of(existing.getFirst());
            }
        }
        if (baseline != null) {
            var previous = access.run(baseline);
            if (!project.equals(previous.get("projectId")))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Baseline run not found");
            if (!List.of("SUCCEEDED", "PARTIALLY_SUCCEEDED").contains(previous.get("status")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Baseline analysis must be complete");
            if (previous.get("artifactsDeletedAt") != null)
                throw new ResponseStatusException(HttpStatus.GONE, "Baseline source has expired");
        }
        if (!db.rows(
                        "SELECT id FROM analysis_run WHERE organization_id=? AND project_id=? AND status IN ('QUEUED','RUNNING') AND config_hash=?",
                        org,
                        project,
                        config)
                .isEmpty())
            return Run.of(db.one(
                    "SELECT * FROM analysis_run WHERE organization_id=? AND project_id=? AND status IN ('QUEUED','RUNNING') AND config_hash=? ORDER BY created_at LIMIT 1",
                    org,
                    project,
                    config));
        var quota = db.one(
                "SELECT count(*) FILTER (WHERE status IN ('QUEUED','RUNNING')) AS active,count(*) FILTER (WHERE created_at>clock_timestamp()-interval '1 hour') AS recent FROM analysis_run WHERE organization_id=?",
                org);
        if (((Number) quota.get("active")).longValue() >= activeLimit
                || ((Number) quota.get("recent")).longValue() >= hourlyLimit)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Organization analysis quota reached");
        UUID run = UUID.randomUUID();
        db.update(
                "INSERT INTO analysis_run(id,organization_id,project_id,baseline_run_id,revision,requested_ref,repository_url,credentials_reference,requested_by,idempotency_key,config_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                run,
                org,
                project,
                baseline,
                ref,
                ref,
                url,
                credentials,
                tenant.userId(),
                key,
                config);
        UUID checkout = queue.enqueue(org, project, run, "CHECKOUT_REPOSITORY", "checkout", Map.of(), List.of(), false);
        UUID detection =
                queue.enqueue(org, project, run, "DETECT_COMPONENTS", "detection", Map.of(), List.of(checkout), false);
        queue.enqueue(org, project, run, "RESOLVE_ANALYZERS", "resolve", Map.of(), List.of(detection), false);
        events.append(org, project, run, "analysis.queued", "Analysis queued", 0);
        audit.record(org, project, tenant.userId(), "ANALYSIS_REQUESTED", Map.of("analysisRunId", run, "ref", ref));
        return get(run);
    }

    @Transactional
    public Run cancel(UUID id) {
        tenant.requireRole("ORG_ADMIN", "PROJECT_ADMIN", "ANALYST", "DEVELOPER");
        var run = access.run(id);
        UUID org = tenant.orgId(), project = (UUID) run.get("projectId");
        access.requireProjectRole(project, "ANALYST", "DEVELOPER");
        var locked = db.one(
                "SELECT * FROM analysis_run WHERE organization_id=? AND project_id=? AND id=? FOR UPDATE",
                org,
                project,
                id);
        if (List.of("QUEUED", "RUNNING").contains(locked.get("status"))) {
            db.update(
                    "UPDATE analysis_run SET status='CANCELLED',current_stage='CANCELLED',cancellation_requested_at=clock_timestamp(),finished_at=clock_timestamp() WHERE organization_id=? AND project_id=? AND id=?",
                    org,
                    project,
                    id);
            db.update(
                    "UPDATE analysis_task SET status='CANCELLED',finished_at=clock_timestamp(),locked_by=NULL,locked_until=NULL,lease_token=NULL WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND status IN ('PENDING','RUNNING')",
                    org,
                    project,
                    id);
            db.update(
                    "UPDATE analyzer_execution SET status='CANCELLED',finished_at=clock_timestamp() WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND status IN ('PLANNED','RUNNING')",
                    org,
                    project,
                    id);
            events.append(
                    org,
                    project,
                    id,
                    "analysis.cancelled",
                    "Analysis cancelled",
                    ((Number) locked.get("progress")).intValue());
            audit.record(org, project, tenant.userId(), "ANALYSIS_CANCELLED", Map.of("analysisRunId", id));
        }
        return get(id);
    }
}
