package io.semanticmap.platform.analysis;

import io.semanticmap.platform.repository.RepositorySnapshots;
import io.semanticmap.platform.repository.RevisionStore;
import io.semanticmap.platform.shared.Db;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
class CheckoutTaskHandler implements TaskHandler {
    private final Db db;
    private final RepositorySnapshots snapshots;
    private final RevisionStore revisions;
    private final AnalysisEvents events;

    CheckoutTaskHandler(Db db, RepositorySnapshots snapshots, RevisionStore revisions, AnalysisEvents events) {
        this.db = db;
        this.snapshots = snapshots;
        this.revisions = revisions;
        this.events = events;
    }

    @Override
    public String type() {
        return "CHECKOUT_REPOSITORY";
    }

    @Override
    public Outcome execute(TaskLease task, BooleanSupplier cancelled) throws Exception {
        var run = db.one(
                "SELECT * FROM analysis_run WHERE organization_id=? AND project_id=? AND id=?",
                task.organizationId(),
                task.projectId(),
                task.runId());
        var snapshot = snapshots.create(
                task.organizationId(),
                task.projectId(),
                task.runId(),
                task.token(),
                (String) run.get("repositoryUrl"),
                (String) run.get("requestedRef"),
                (String) run.get("credentialsReference"),
                cancelled);
        try {
            Path baseline = null;
            if (run.get("baselineRunId") != null) {
                var previous = db.one(
                        "SELECT workspace_path FROM analysis_run WHERE organization_id=? AND project_id=? AND id=?",
                        task.organizationId(),
                        task.projectId(),
                        run.get("baselineRunId"));
                if (previous.get("workspacePath") == null) throw new java.io.IOException("BASELINE_SOURCE_EXPIRED");
                baseline = Path.of((String) previous.get("workspacePath"));
            }
            var changed = snapshots.changedFiles(snapshot, baseline);
            return new Outcome(
                    () -> {
                        var revision = revisions.record(
                                task.organizationId(), task.projectId(), snapshot, (String) run.get("requestedRef"));
                        UUID revisionId = (UUID) revision.get("id");
                        db.update(
                                "UPDATE analysis_run SET revision_id=?,revision=?,workspace_path=?,progress=15 WHERE organization_id=? AND project_id=? AND id=?",
                                revisionId,
                                snapshot.commitSha(),
                                snapshot.workspace().toString(),
                                task.organizationId(),
                                task.projectId(),
                                task.runId());
                        for (var file : changed)
                            db.update(
                                    "INSERT INTO revision_changed_file(organization_id,project_id,revision_id,analysis_run_id,file_path,change_type) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                                    task.organizationId(),
                                    task.projectId(),
                                    revisionId,
                                    task.runId(),
                                    file.path(),
                                    file.changeType());
                        events.append(
                                task.organizationId(),
                                task.projectId(),
                                task.runId(),
                                "repository.checkout.completed",
                                "Immutable source snapshot created",
                                15);
                    },
                    () -> snapshots.remove(snapshot.workspace().getParent()));
        } catch (Exception e) {
            snapshots.remove(snapshot.workspace().getParent());
            throw e;
        }
    }
}
