package io.semanticmap.platform.shared;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class Audit {
    private final Db db;

    public Audit(Db db) {
        this.db = db;
    }

    public void record(UUID org, UUID project, UUID user, String action, Map<String, Object> metadata) {
        db.update(
                "INSERT INTO audit_event(id,organization_id,project_id,user_id,action,metadata) VALUES (?,?,?,?,?,?::jsonb)",
                UUID.randomUUID(),
                org,
                project,
                user,
                action,
                db.json(metadata));
    }
}
