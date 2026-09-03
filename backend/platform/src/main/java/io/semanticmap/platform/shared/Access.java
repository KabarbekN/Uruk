package io.semanticmap.platform.shared;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class Access {
    private final Db db;
    private final TenantContext tenant;

    public Access(Db db, TenantContext tenant) {
        this.db = db;
        this.tenant = tenant;
    }

    public Map<String, Object> project(UUID id) {
        return db.one(
                "SELECT p.* FROM project p WHERE p.id=? AND p.organization_id=? AND p.deleted_at IS NULL AND (? OR EXISTS (SELECT 1 FROM project_member m WHERE m.project_id=p.id AND m.organization_id=p.organization_id AND m.user_id=?))",
                id,
                tenant.orgId(),
                tenant.hasRole("ORG_ADMIN"),
                tenant.userId());
    }

    public Map<String, Object> run(UUID id) {
        var run = db.one(
                "SELECT r.* FROM analysis_run r JOIN project p ON p.id=r.project_id AND p.organization_id=r.organization_id WHERE r.id=? AND r.organization_id=? AND p.deleted_at IS NULL",
                id,
                tenant.orgId());
        project((UUID) run.get("projectId"));
        return run;
    }

    public void requireProjectRole(UUID project, String... roles) {
        project(project);
        if (tenant.hasRole("ORG_ADMIN")) return;
        var member = db.one(
                "SELECT role FROM project_member WHERE organization_id=? AND project_id=? AND user_id=?",
                tenant.orgId(),
                project,
                tenant.userId());
        String role = member.get("role").toString();
        if (!role.equals("PROJECT_ADMIN") && Arrays.stream(roles).noneMatch(role::equals))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient project role");
    }
}
