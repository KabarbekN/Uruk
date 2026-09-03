package io.semanticmap.platform.project;

import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/projects")
class ProjectController {
    private final ProjectRepository projects;
    private final TenantContext tenant;
    private final Audit audit;
    private final Db db;
    private final Access access;

    ProjectController(ProjectRepository projects, TenantContext tenant, Audit audit, Db db, Access access) {
        this.projects = projects;
        this.tenant = tenant;
        this.audit = audit;
        this.db = db;
        this.access = access;
    }

    public record Input(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description,
            @Size(max = 2000) String repositoryPath) {}

    public record View(UUID id, String name, String description, String repositoryPath, Instant createdAt) {
        static View of(ProjectEntity p) {
            return new View(p.id, p.name, p.description, p.repositoryPath, p.createdAt);
        }
    }

    @GetMapping
    List<View> list() {
        return projects.visible(tenant.orgId(), tenant.userId(), tenant.hasRole("ORG_ADMIN")).stream()
                .map(View::of)
                .toList();
    }

    @GetMapping("/{id}")
    View get(@PathVariable UUID id) {
        return View.of(find(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    View create(@Valid @RequestBody Input input) {
        tenant.requireRole("PROJECT_ADMIN");
        var p = projects.save(new ProjectEntity(
                tenant.orgId(),
                input.name().trim(),
                input.description() == null ? "" : input.description(),
                input.repositoryPath() == null ? "" : input.repositoryPath()));
        projects.flush();
        db.update(
                "INSERT INTO project_member(organization_id,project_id,user_id,role) VALUES (?,?,?,?)",
                tenant.orgId(),
                p.id,
                tenant.userId(),
                "PROJECT_ADMIN");
        audit.record(tenant.orgId(), p.id, tenant.userId(), "PROJECT_CREATED", Map.of("name", p.name));
        return View.of(p);
    }

    @PatchMapping("/{id}")
    @Transactional
    View update(@PathVariable UUID id, @Valid @RequestBody Input input) {
        tenant.requireRole("PROJECT_ADMIN");
        access.requireProjectRole(id, "PROJECT_ADMIN");
        var p = find(id);
        p.name = input.name().trim();
        if (input.description() != null) p.description = input.description();
        if (input.repositoryPath() != null) p.repositoryPath = input.repositoryPath();
        p.updatedAt = Instant.now();
        audit.record(tenant.orgId(), id, tenant.userId(), "PROJECT_UPDATED", Map.of());
        return View.of(projects.save(p));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable UUID id) {
        tenant.requireRole("PROJECT_ADMIN");
        access.requireProjectRole(id, "PROJECT_ADMIN");
        // Match analysis-start lock ordering so archive and start cannot race.
        db.one("SELECT id FROM organization WHERE id=? FOR UPDATE", tenant.orgId());
        db.one("SELECT id FROM project WHERE id=? AND organization_id=? FOR UPDATE", id, tenant.orgId());
        var p = find(id);
        if (!db.rows(
                        "SELECT id FROM analysis_run WHERE project_id=? AND organization_id=? AND status IN ('QUEUED','RUNNING') LIMIT 1",
                        id,
                        tenant.orgId())
                .isEmpty())
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Cancel active analyses before deleting the project");
        audit.record(tenant.orgId(), id, tenant.userId(), "PROJECT_ARCHIVED", Map.of("name", p.name));
        p.deletedAt = Instant.now();
        projects.save(p);
    }

    private ProjectEntity find(UUID id) {
        access.project(id);
        return projects.findByIdAndOrganizationId(id, tenant.orgId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    }
}
