package io.semanticmap.platform.project;

import static org.assertj.core.api.Assertions.*;

import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"semantic.worker.enabled=false", "semantic.metrics.initial-delay-ms=3600000"})
@ActiveProfiles("dev")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TenantIsolationIT {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    Db db;

    @Autowired
    Access access;

    @Autowired
    ProjectRepository projects;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void projectLookupsAreTenantScopedAndMigrationsRun() {
        UUID otherOrg = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        db.update("INSERT INTO organization(id,name) VALUES (?,?)", otherOrg, "Other tenant");
        db.update(
                "INSERT INTO project(id,organization_id,name) VALUES (?,?,?)",
                projectId,
                otherOrg,
                "Private repository");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new TenantContext.Principal(TenantContext.DEV_ORG, TenantContext.DEV_USER),
                        "",
                        List.of(new SimpleGrantedAuthority("ROLE_ORG_ADMIN"))));
        assertThat(projects.findByIdAndOrganizationId(projectId, TenantContext.DEV_ORG))
                .isEmpty();
        assertThatThrownBy(() -> access.project(projectId)).isInstanceOf(ResponseStatusException.class);
        assertThat(db.rows("SELECT version FROM flyway_schema_history WHERE success=true"))
                .hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void organizationRoleDoesNotGrantAccessToUnassignedProjects() {
        UUID projectId = UUID.randomUUID();
        db.update(
                "INSERT INTO project(id,organization_id,name) VALUES (?,?,?)",
                projectId,
                TenantContext.DEV_ORG,
                "Member-only project");
        authenticate("ANALYST");
        assertThatThrownBy(() -> access.project(projectId)).isInstanceOf(ResponseStatusException.class);
        db.update(
                "INSERT INTO project_member(organization_id,project_id,user_id,role) VALUES (?,?,?,?)",
                TenantContext.DEV_ORG,
                projectId,
                TenantContext.DEV_USER,
                "VIEWER");
        assertThat(access.project(projectId).get("id")).isEqualTo(projectId);
        assertThatThrownBy(() -> access.requireProjectRole(projectId, "ANALYST"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
        db.update("UPDATE project_member SET role='ANALYST' WHERE project_id=?", projectId);
        assertThatCode(() -> access.requireProjectRole(projectId, "ANALYST")).doesNotThrowAnyException();
    }

    @Test
    void archivedProjectIsNotAccessibleEvenToOrganizationAdministrator() {
        UUID projectId = UUID.randomUUID();
        db.update(
                "INSERT INTO project(id,organization_id,name,deleted_at) VALUES (?,?,?,now())",
                projectId,
                TenantContext.DEV_ORG,
                "Archived project");
        authenticate("ORG_ADMIN");
        assertThatThrownBy(() -> access.project(projectId)).isInstanceOf(ResponseStatusException.class);
    }

    private static void authenticate(String role) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new TenantContext.Principal(TenantContext.DEV_ORG, TenantContext.DEV_USER),
                        "",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
