package io.semanticmap.platform.identity;

import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class MembershipController {
    private static final List<String> ROLES =
            List.of("ORG_ADMIN", "PROJECT_ADMIN", "ANALYST", "DEVELOPER", "REVIEWER", "VIEWER");
    private final Db db;
    private final TenantContext tenant;
    private final Access access;
    private final Audit audit;

    MembershipController(Db db, TenantContext tenant, Access access, Audit audit) {
        this.db = db;
        this.tenant = tenant;
        this.access = access;
        this.audit = audit;
    }

    record OrganizationInput(@NotBlank @Size(max = 120) String name) {}

    record MemberInput(
            @NotNull UUID userId,
            @NotBlank @Size(max = 300) String subject,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank String role) {}

    record ProjectMemberInput(@NotNull UUID userId, @NotBlank String role) {}

    @PostMapping("/organization")
    @Transactional
    Map<String, Object> initialize(@Valid @RequestBody OrganizationInput input) {
        tenant.requireRole("ORG_ADMIN");
        db.update(
                "INSERT INTO organization(id,name) VALUES (?,?) ON CONFLICT(id) DO NOTHING",
                tenant.orgId(),
                input.name());
        db.update(
                "INSERT INTO user_account(id,subject,display_name) VALUES (?,?,?) ON CONFLICT(id) DO NOTHING",
                tenant.userId(),
                tenant.userId().toString(),
                "Organization admin");
        db.update(
                "INSERT INTO organization_member(organization_id,user_id,role) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                tenant.orgId(),
                tenant.userId(),
                "ORG_ADMIN");
        return db.one("SELECT id,name,created_at FROM organization WHERE id=?", tenant.orgId());
    }

    @GetMapping("/organization/members")
    List<Map<String, Object>> members() {
        tenant.requireRole("ORG_ADMIN");
        return db.rows(
                "SELECT m.user_id,m.role,u.display_name FROM organization_member m JOIN user_account u ON u.id=m.user_id WHERE m.organization_id=?",
                tenant.orgId());
    }

    @PutMapping("/organization/members")
    @Transactional
    void member(@Valid @RequestBody MemberInput input) {
        tenant.requireRole("ORG_ADMIN");
        requireRole(input.role());
        db.update(
                "INSERT INTO user_account(id,subject,display_name) VALUES (?,?,?) ON CONFLICT(id) DO NOTHING",
                input.userId(),
                input.subject(),
                input.displayName());
        db.update(
                "INSERT INTO organization_member(organization_id,user_id,role) VALUES (?,?,?) ON CONFLICT(organization_id,user_id) DO UPDATE SET role=EXCLUDED.role",
                tenant.orgId(),
                input.userId(),
                input.role());
        audit.record(
                tenant.orgId(),
                null,
                tenant.userId(),
                "ORGANIZATION_MEMBER_UPDATED",
                Map.of("userId", input.userId(), "role", input.role()));
    }

    @GetMapping("/projects/{id}/members")
    List<Map<String, Object>> projectMembers(@PathVariable UUID id) {
        access.requireProjectRole(id, "PROJECT_ADMIN");
        tenant.requireRole("PROJECT_ADMIN");
        return db.rows(
                "SELECT m.user_id,m.role,u.display_name FROM project_member m JOIN user_account u ON u.id=m.user_id WHERE m.organization_id=? AND m.project_id=?",
                tenant.orgId(),
                id);
    }

    @PutMapping("/projects/{id}/members")
    @Transactional
    void projectMember(@PathVariable UUID id, @Valid @RequestBody ProjectMemberInput input) {
        access.requireProjectRole(id, "PROJECT_ADMIN");
        tenant.requireRole("PROJECT_ADMIN");
        requireRole(input.role());
        db.one(
                "SELECT user_id FROM organization_member WHERE organization_id=? AND user_id=?",
                tenant.orgId(),
                input.userId());
        db.update(
                "INSERT INTO project_member(organization_id,project_id,user_id,role) VALUES (?,?,?,?) ON CONFLICT(project_id,user_id) DO UPDATE SET role=EXCLUDED.role",
                tenant.orgId(),
                id,
                input.userId(),
                input.role());
        audit.record(
                tenant.orgId(),
                id,
                tenant.userId(),
                "PROJECT_MEMBER_UPDATED",
                Map.of("userId", input.userId(), "role", input.role()));
    }

    @GetMapping("/organization/audit")
    List<Map<String, Object>> audit(@RequestParam(defaultValue = "100") int limit) {
        tenant.requireRole("ORG_ADMIN");
        return db.rows(
                "SELECT id,project_id,user_id,action,metadata,created_at FROM audit_event WHERE organization_id=? ORDER BY created_at DESC LIMIT ?",
                tenant.orgId(),
                Math.max(1, Math.min(limit, 500)));
    }

    private void requireRole(String role) {
        if (!ROLES.contains(role)) throw new IllegalArgumentException("Unknown role");
    }
}
