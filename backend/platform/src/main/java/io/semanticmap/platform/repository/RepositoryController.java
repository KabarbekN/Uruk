package io.semanticmap.platform.repository;

import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RepositoryController {
    private final Db db;
    private final TenantContext tenant;
    private final Access access;
    private final Audit audit;
    private final RepositoryPolicy policy;
    private final GitClient git;
    private final RepositorySnapshots snapshots;
    private final RevisionStore revisions;
    private final CredentialStore credentialStore;

    public RepositoryController(
            Db db,
            TenantContext tenant,
            Access access,
            Audit audit,
            RepositoryPolicy policy,
            GitClient git,
            RepositorySnapshots snapshots,
            RevisionStore revisions,
            CredentialStore credentialStore) {
        this.db = db;
        this.tenant = tenant;
        this.access = access;
        this.audit = audit;
        this.policy = policy;
        this.git = git;
        this.snapshots = snapshots;
        this.revisions = revisions;
        this.credentialStore = credentialStore;
    }

    public record Connect(
            @NotBlank @Size(max = 2048) String url,
            @Size(max = 200) String branch,
            @Size(max = 100) String credentialsReference,
            @Size(max = 1000) String personalAccessToken) {}

    public record Fetch(String ref) {}

    public record TestAccess(
            @NotBlank @Size(max = 2048) String url,
            @Size(max = 1000) String token,
            @Size(max = 100) String credentialsReference) {}

    public record TestAccessResult(boolean connected, List<String> branches, String defaultBranch) {}

    @PostMapping("/api/v1/repositories/test-access")
    public TestAccessResult testAccess(@Valid @RequestBody TestAccess request) throws IOException {
        String token = request.token();
        if ((token == null || token.isBlank()) && request.credentialsReference() != null) {
            token = credentialStore.resolveToken(tenant.orgId(), request.credentialsReference());
            if (token == null) {
                token = request.credentialsReference();
            }
        }
        List<String> branches = git.listBranches(request.url(), token != null && !token.isBlank() ? token : null);
        String defaultBranch = branches.contains("main")
                ? "main"
                : (branches.contains("master") ? "master" : (branches.isEmpty() ? "main" : branches.getFirst()));
        return new TestAccessResult(true, branches, defaultBranch);
    }

    @PostMapping("/api/v1/projects/{project}/repositories")
    @Transactional
    public Map<String, Object> connect(@PathVariable UUID project, @Valid @RequestBody Connect request)
            throws IOException {
        tenant.requireRole("ORG_ADMIN", "PROJECT_ADMIN");
        access.project(project);
        access.requireProjectRole(project, "PROJECT_ADMIN");
        policy.validate(request.url());
        String credRef = request.credentialsReference();
        if (request.personalAccessToken() != null
                && !request.personalAccessToken().isBlank()) {
            credRef = credentialStore.storeToken(
                    tenant.orgId(), request.personalAccessToken().trim(), "Token for " + request.url());
        }
        policy.validateCredentials(credRef);
        String branch = request.branch() == null ? "HEAD" : request.branch();
        policy.validateRef(branch);
        var result = db.one(
                "INSERT INTO repository_connection(id,organization_id,project_id,url,branch,credentials_reference) VALUES (?,?,?,?,?,?) ON CONFLICT (organization_id,project_id) DO UPDATE SET url=excluded.url,branch=excluded.branch,credentials_reference=excluded.credentials_reference RETURNING *",
                UUID.randomUUID(),
                tenant.orgId(),
                project,
                request.url(),
                branch,
                credRef);
        audit.record(
                tenant.orgId(),
                project,
                tenant.userId(),
                "REPOSITORY_CONNECTED",
                Map.of("repositoryId", result.get("id")));
        return result;
    }

    @GetMapping("/api/v1/projects/{project}/repositories")
    public List<Map<String, Object>> list(@PathVariable UUID project) {
        access.project(project);
        return db.rows(
                "SELECT id,project_id,url,branch,credentials_reference,created_at FROM repository_connection WHERE organization_id=? AND project_id=?",
                tenant.orgId(),
                project);
    }

    @PostMapping("/api/v1/repositories/{repository}/test-connection")
    public Map<String, Object> test(@PathVariable UUID repository) throws IOException {
        var row = connection(repository);
        mutation((UUID) row.get("projectId"));
        git.test((String) row.get("url"), (String) row.get("branch"), (String) row.get("credentialsReference"));
        return Map.of("repositoryId", repository, "connected", true);
    }

    @PostMapping("/api/v1/repositories/{repository}/fetch")
    public Map<String, Object> fetch(@PathVariable UUID repository, @RequestBody(required = false) Fetch request)
            throws IOException {
        var row = connection(repository);
        UUID project = (UUID) row.get("projectId");
        mutation(project);
        String ref = request == null || request.ref() == null ? (String) row.get("branch") : request.ref();
        var snapshot = snapshots.create(
                tenant.orgId(),
                project,
                UUID.randomUUID(),
                UUID.randomUUID(),
                (String) row.get("url"),
                ref,
                (String) row.get("credentialsReference"),
                () -> false);
        try {
            var result = revisions.record(tenant.orgId(), project, snapshot, ref);
            audit.record(
                    tenant.orgId(),
                    project,
                    tenant.userId(),
                    "REPOSITORY_FETCHED",
                    Map.of("repositoryId", repository, "revisionId", result.get("id")));
            return result;
        } finally {
            snapshots.remove(snapshot.workspace().getParent().getParent());
        }
    }

    @GetMapping("/api/v1/repositories/{repository}/revisions")
    public List<Map<String, Object>> revisions(@PathVariable UUID repository) {
        var row = connection(repository);
        return db.rows(
                "SELECT id,project_id,commit_sha,branch,fingerprint,created_at FROM revision WHERE organization_id=? AND project_id=? ORDER BY created_at DESC LIMIT 200",
                tenant.orgId(),
                row.get("projectId"));
    }

    private Map<String, Object> connection(UUID id) {
        var row = db.one("SELECT * FROM repository_connection WHERE organization_id=? AND id=?", tenant.orgId(), id);
        access.project((UUID) row.get("projectId"));
        return row;
    }

    private void mutation(UUID project) {
        tenant.requireRole("ORG_ADMIN", "PROJECT_ADMIN");
        access.requireProjectRole(project, "PROJECT_ADMIN");
    }
}
