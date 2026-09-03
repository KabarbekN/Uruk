package io.semanticmap.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.semanticmap.platform.shared.Db;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
        properties = {
            "semantic.worker.enabled=false",
            "semantic.metrics.initial-delay-ms=3600000",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.test"
        })
@AutoConfigureMockMvc
@ActiveProfiles("production")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SecurityHttpIT {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    MockMvc http;

    @Autowired
    Db db;

    @MockitoBean
    JwtDecoder decoder;

    private UUID org, user, project;

    @BeforeEach
    void setup() {
        org = UUID.randomUUID();
        user = UUID.randomUUID();
        project = UUID.randomUUID();
        db.update("INSERT INTO organization(id,name) VALUES (?,?)", org, "HTTP test organization");
        db.update(
                "INSERT INTO user_account(id,subject,display_name) VALUES (?,?,?)",
                user,
                user.toString(),
                "HTTP test user");
        db.update("INSERT INTO project(id,organization_id,name) VALUES (?,?,?)", project, org, "Private project");
        token("admin", org, "ORG_ADMIN");
        token("viewer", org, "VIEWER");
        token("other", UUID.randomUUID(), "ORG_ADMIN");
    }

    @Test
    void anonymousApiIsRejectedButHealthRemainsPublic() throws Exception {
        http.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());
        http.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void trustedOrganizationClaimsDoNotBypassTenantScoping() throws Exception {
        http.perform(get("/api/v1/projects/" + project).header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("id").value(project.toString()));
        http.perform(get("/api/v1/projects/" + project).header("Authorization", "Bearer other"))
                .andExpect(status().isNotFound());
    }

    @Test
    void readMembershipDoesNotGrantMutationPrivileges() throws Exception {
        http.perform(get("/api/v1/projects/" + project).header("Authorization", "Bearer viewer"))
                .andExpect(status().isNotFound());
        db.update(
                "INSERT INTO project_member(organization_id,project_id,user_id,role) VALUES (?,?,?,?)",
                org,
                project,
                user,
                "VIEWER");
        http.perform(get("/api/v1/projects/" + project).header("Authorization", "Bearer viewer"))
                .andExpect(status().isOk());
        http.perform(patch("/api/v1/projects/" + project)
                        .header("Authorization", "Bearer viewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Unauthorized rename\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unrecognizedRoleIsNotPromotedToAdministrator() throws Exception {
        token("unknown", org, "SUPER_ADMIN");
        http.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Not authorized\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void archivePreservesAuditButHidesProject() throws Exception {
        http.perform(delete("/api/v1/projects/" + project).header("Authorization", "Bearer admin"))
                .andExpect(status().isNoContent());
        http.perform(get("/api/v1/projects/" + project).header("Authorization", "Bearer admin"))
                .andExpect(status().isNotFound());
        assertThat(db.rows("SELECT id FROM audit_event WHERE project_id=? AND action='PROJECT_ARCHIVED'", project))
                .hasSize(1);
    }

    private void token(String value, UUID organization, String role) {
        when(decoder.decode(value))
                .thenReturn(Jwt.withTokenValue(value)
                        .header("alg", "RS256")
                        .subject(user.toString())
                        .claim("organization_id", organization.toString())
                        .claim("user_id", user.toString())
                        .claim("roles", List.of(role))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build());
    }
}
