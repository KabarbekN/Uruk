package io.semanticmap.platform.analysis;

import io.semanticmap.platform.detection.TechnologyDetector;
import io.semanticmap.platform.shared.Db;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
class DetectionTaskHandler implements TaskHandler {
    private final Db db;
    private final TechnologyDetector detector;
    private final AnalysisEvents events;

    DetectionTaskHandler(Db db, TechnologyDetector detector, AnalysisEvents events) {
        this.db = db;
        this.detector = detector;
        this.events = events;
    }

    @Override
    public String type() {
        return "DETECT_COMPONENTS";
    }

    @Override
    public Outcome execute(TaskLease task, BooleanSupplier cancelled) throws Exception {
        var run = db.one(
                "SELECT revision_id,workspace_path FROM analysis_run WHERE organization_id=? AND project_id=? AND id=?",
                task.organizationId(),
                task.projectId(),
                task.runId());
        var components = detector.detect(Path.of((String) run.get("workspacePath")));
        return new Outcome(() -> {
            for (var component : components)
                db.update(
                        "INSERT INTO technology_component(id,organization_id,project_id,revision_id,analysis_run_id,root_path,languages,frameworks,build_systems,database_technologies,evidence,fingerprint,diagnostics) VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?::jsonb) ON CONFLICT (organization_id,project_id,analysis_run_id,root_path) DO NOTHING",
                        UUID.randomUUID(),
                        task.organizationId(),
                        task.projectId(),
                        run.get("revisionId"),
                        task.runId(),
                        component.rootPath(),
                        db.json(component.languages()),
                        db.json(component.frameworks()),
                        db.json(component.buildSystems()),
                        db.json(component.databaseTechnologies()),
                        db.json(component.evidence()),
                        component.fingerprint(),
                        db.json(component.diagnostics()));
            db.update(
                    "UPDATE analysis_run SET progress=25 WHERE organization_id=? AND project_id=? AND id=?",
                    task.organizationId(),
                    task.projectId(),
                    task.runId());
            events.append(
                    task.organizationId(),
                    task.projectId(),
                    task.runId(),
                    "technology.detected",
                    components.size() + " technology components detected",
                    25);
        });
    }
}
