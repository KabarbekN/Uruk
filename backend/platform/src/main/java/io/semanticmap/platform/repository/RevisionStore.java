package io.semanticmap.platform.repository;

import io.semanticmap.platform.shared.Db;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RevisionStore {
    private final Db db;

    public RevisionStore(Db db) {
        this.db = db;
    }

    @Transactional
    public Map<String, Object> record(UUID org, UUID project, RepositorySnapshots.Snapshot snapshot, String branch) {
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO revision(id,organization_id,project_id,commit_sha,branch,fingerprint) VALUES (?,?,?,?,?,?) ON CONFLICT (organization_id,project_id,commit_sha,fingerprint) DO NOTHING",
                id,
                org,
                project,
                snapshot.commitSha(),
                branch,
                snapshot.fingerprint());
        var row = db.one(
                "SELECT * FROM revision WHERE organization_id=? AND project_id=? AND commit_sha=? AND fingerprint=?",
                org,
                project,
                snapshot.commitSha(),
                snapshot.fingerprint());
        id = (UUID) row.get("id");
        for (int i = 0; i < snapshot.parents().size(); i++) {
            db.update(
                    "INSERT INTO revision_parent(organization_id,project_id,revision_id,parent_commit_sha,ordinal) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                    org,
                    project,
                    id,
                    snapshot.parents().get(i),
                    i);
        }
        return row;
    }
}
