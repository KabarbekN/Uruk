package io.semanticmap.platform.analysis;

import io.semanticmap.platform.shared.Db;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AnalysisEvents {
    private final Db db;

    public AnalysisEvents(Db db) {
        this.db = db;
    }

    @Transactional
    public void append(UUID org, UUID project, UUID run, String type, String message, int progress) {
        // Serialize writers per run so sequence allocation cannot overtake an uncommitted event.
        db.one(
                "SELECT id FROM analysis_run WHERE organization_id=? AND project_id=? AND id=? FOR UPDATE",
                org,
                project,
                run);
        db.update(
                "INSERT INTO analysis_event(organization_id,project_id,analysis_run_id,type,message,progress) VALUES (?,?,?,?,?,?)",
                org,
                project,
                run,
                type,
                message,
                progress);
    }

    public List<Map<String, Object>> after(UUID org, UUID project, UUID run, long sequence) {
        return db.rows(
                "SELECT sequence_number,type,message,progress,created_at FROM analysis_event WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND sequence_number>? ORDER BY sequence_number LIMIT 500",
                org,
                project,
                run,
                sequence);
    }
}
