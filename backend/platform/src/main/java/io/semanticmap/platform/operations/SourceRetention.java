package io.semanticmap.platform.operations;

import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.storage.ArtifactStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "semantic.worker.enabled", havingValue = "true", matchIfMissing = true)
class SourceRetention {
    private static final Logger log = LoggerFactory.getLogger(SourceRetention.class);
    private final Db db;
    private final Audit audit;
    private final Path artifactRoot;
    private final ArtifactStore artifacts;

    SourceRetention(
            Db db,
            Audit audit,
            ArtifactStore artifacts,
            @Value("${semantic.artifact-root:.runtime/artifacts}") String root) {
        this.db = db;
        this.audit = audit;
        this.artifacts = artifacts;
        artifactRoot = Path.of(root).toAbsolutePath().normalize();
    }

    @Scheduled(
            fixedDelayString = "${semantic.retention.interval-ms:3600000}",
            initialDelayString = "${semantic.retention.initial-delay-ms:3600000}")
    void cleanup() {
        var runs = db.rows(
                "SELECT r.id,r.organization_id,r.project_id,r.workspace_path FROM analysis_run r JOIN organization o ON o.id=r.organization_id WHERE r.status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELLED') AND r.finished_at < now()-make_interval(days=>o.retention_days) AND r.workspace_path IS NOT NULL LIMIT 20");
        for (var run : runs) {
            try {
                Path workspace = Path.of(run.get("workspacePath").toString())
                        .toAbsolutePath()
                        .normalize();
                deleteRunArtifacts(
                        artifacts,
                        artifactRoot,
                        (UUID) run.get("organizationId"),
                        (UUID) run.get("projectId"),
                        (UUID) run.get("id"),
                        workspace);
                db.update(
                        "UPDATE evidence SET snippet='',source_available=false,expired_at=now() WHERE analysis_run_id=? AND organization_id=? AND project_id=? AND source_available",
                        run.get("id"),
                        run.get("organizationId"),
                        run.get("projectId"));
                db.update(
                        "UPDATE fact_quarantine SET raw_line='' WHERE analysis_run_id=? AND organization_id=? AND project_id=?",
                        run.get("id"),
                        run.get("organizationId"),
                        run.get("projectId"));
                db.update(
                        "UPDATE analysis_run SET workspace_path=NULL,artifacts_deleted_at=now() WHERE id=? AND organization_id=? AND project_id=?",
                        run.get("id"),
                        run.get("organizationId"),
                        run.get("projectId"));
                audit.record(
                        (UUID) run.get("organizationId"),
                        (UUID) run.get("projectId"),
                        null,
                        "SOURCE_EXPIRED",
                        Map.of("analysisRunId", run.get("id")));
            } catch (IOException | IllegalArgumentException e) {
                log.warn(
                        "Retention refused run={} reason={}",
                        run.get("id"),
                        e.getClass().getSimpleName());
            }
        }
    }

    static void deleteRunArtifacts(
            ArtifactStore artifacts, Path root, UUID organization, UUID project, UUID run, Path workspace)
            throws IOException {
        Path runRoot = runArtifactRoot(root, organization, project, run, workspace);
        artifacts.deleteTree(root.relativize(runRoot).toString().replace('\\', '/'));
    }

    private static Path runArtifactRoot(Path root, UUID organization, UUID project, UUID run, Path workspace) {
        Path runRoot = root.resolve("workspaces")
                .resolve(organization.toString())
                .resolve(project.toString())
                .resolve(run.toString());
        if (workspace.getParent() == null
                || !workspace.getFileName().toString().equals("source")
                || !runRoot.equals(workspace.getParent().getParent()))
            throw new IllegalArgumentException("Unexpected run artifact path");
        UUID.fromString(workspace.getParent().getFileName().toString());
        return runRoot;
    }
}
