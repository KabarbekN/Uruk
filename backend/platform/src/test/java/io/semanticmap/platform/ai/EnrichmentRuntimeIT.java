package io.semanticmap.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.semanticmap.platform.runtime.OtlpParser;
import io.semanticmap.platform.runtime.RuntimeService;
import io.semanticmap.platform.settings.AiConfiguration;
import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import jakarta.validation.Validation;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class EnrichmentRuntimeIT {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

    private final UUID org = UUID.randomUUID(),
            project = UUID.randomUUID(),
            run = UUID.randomUUID(),
            revision = UUID.randomUUID(),
            node = UUID.randomUUID(),
            fact = UUID.randomUUID(),
            user = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final TenantContext tenant = mock(TenantContext.class);
    private Db db;
    private Access access;
    private Audit audit;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transactions;
    private final String condition = "{\"left\":\"order.total\",\"operator\":\"<\",\"right\":500}";

    @BeforeEach
    void setup() {
        var dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new Db(new JdbcTemplate(dataSource), mapper);
        audit = new Audit(db);
        access = new Access(db, tenant);
        manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
        when(tenant.orgId()).thenReturn(org);
        when(tenant.userId()).thenReturn(user);
        when(tenant.hasRole("ORG_ADMIN")).thenReturn(true);
        db.update("INSERT INTO organization(id,name,ai_mode) VALUES (?,?,'LOCAL_PROVIDER')", org, "AI runtime tests");
        db.update(
                "INSERT INTO user_account(id,subject,display_name) VALUES (?,?,?)", user, user.toString(), "Test user");
        db.update("INSERT INTO project(id,organization_id,name) VALUES (?,?,?)", project, org, "Tests");
        db.update(
                "INSERT INTO revision(id,organization_id,project_id,commit_sha,branch,fingerprint) VALUES (?,?,?,'abc','main','fingerprint')",
                revision,
                org,
                project);
        db.update(
                "INSERT INTO analysis_run(id,organization_id,project_id,revision_id,status,repository_url,requested_by,config_hash) VALUES (?,?,?,?,'SUCCEEDED','/test',?,'config')",
                run,
                org,
                project,
                revision,
                user);
        UUID component = UUID.randomUUID(), execution = UUID.randomUUID(), evidence = UUID.randomUUID();
        db.update(
                "INSERT INTO technology_component(id,organization_id,project_id,revision_id,analysis_run_id,root_path,languages,frameworks,build_systems,database_technologies,evidence,fingerprint) VALUES (?,?,?,?,?,'src','[]','[]','[]','[]','[]','component')",
                component,
                org,
                project,
                revision,
                run);
        db.update(
                "INSERT INTO analyzer_execution(id,organization_id,project_id,revision_id,analysis_run_id,component_id,analyzer_key,version,image_reference,status,reason,requested_capabilities) VALUES (?,?,?,?,?,?,'java-spring','0.1.0','test-image','SUCCEEDED','test','[]')",
                execution,
                org,
                project,
                revision,
                run,
                component);
        db.update(
                "INSERT INTO raw_fact(id,organization_id,project_id,revision_id,analysis_run_id,analyzer_execution_id,fact_id,stable_key,kind,origin,confidence,payload,fingerprint) VALUES (?,?,?,?,?,?,'fact-1','rule:order','CONDITION','STATIC_TYPED',0.9,?::jsonb,'fact-fingerprint')",
                fact,
                org,
                project,
                revision,
                run,
                execution,
                "{\"properties\":{\"normalizedCondition\":" + condition + "}}");
        db.update(
                "INSERT INTO evidence(id,organization_id,project_id,analysis_run_id,revision_id,raw_fact_id,analyzer_execution_id,file_path,start_line,end_line,start_column,end_column,snippet,snippet_hash,analyzer_id,analyzer_version,origin) VALUES (?,?,?,?,?,?,?,'src/Order.java',1,1,1,20,'if (total < 500)','source-hash','java-spring','0.1.0','STATIC_TYPED')",
                evidence,
                org,
                project,
                run,
                revision,
                fact,
                execution);
        db.update(
                "INSERT INTO semantic_node(id,organization_id,project_id,revision_id,analysis_run_id,stable_key,kind,name,label,confidence,support_level,properties,source_fact_ids,evidence_ids,fingerprint,structural_fingerprint,evidence_fingerprint) VALUES (?,?,?,?,?,'rule:order','BUSINESS_RULE','Order','Order',0.9,'SUPPORTED',?::jsonb,?::jsonb,?::jsonb,'node-fingerprint','structure','evidence')",
                node,
                org,
                project,
                revision,
                run,
                "{\"symbolSignature\":\"io.example.Order#create()\"}",
                db.json(List.of(fact.toString())),
                db.json(List.of(evidence.toString())));
    }

    @Test
    void enrichmentCachesOwnedEvidenceAndNeverMutatesSourceFacts() {
        var loader = new EvidenceBundle.Loader(db, tenant, access, mapper, new SecretRedactor(), List.of("**"));
        AtomicInteger providerCalls = new AtomicInteger();
        EnrichmentGateway gateway = (bundle, beforeAttempt) -> {
            beforeAttempt.run();
            providerCalls.incrementAndGet();
            return new EnrichmentGateway.Reply(
                    db.json(Map.of(
                            "category",
                            "BUSINESS_RULE",
                            "supportedFactIds",
                            List.of(fact.toString()),
                            "claims",
                            List.of(Map.of(
                                    "factId", fact.toString(), "field", "normalizedCondition", "value", condition)),
                            "ambiguities",
                            List.of())),
                    100,
                    20);
        };
        var config = new AiConfiguration("http://127.0.0.1:11434", "test-model", "", "127.0.0.1", 2);
        var service = new EnrichmentService(
                db,
                tenant,
                audit,
                access,
                config,
                loader,
                gateway,
                new EnrichmentValidator(
                        Validation.buildDefaultValidatorFactory().getValidator()),
                manager);
        assertThat(service.enrich(node)).containsEntry("status", "SUCCEEDED").containsEntry("cached", false);
        assertThat(service.enrich(node)).containsEntry("cached", true);
        assertThat(providerCalls.get()).isEqualTo(1);
        assertThat(db.one(
                        "SELECT status,input_tokens,output_tokens,attempts FROM llm_execution WHERE organization_id=?",
                        org))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("inputTokens", 100L)
                .containsEntry("outputTokens", 20L)
                .containsEntry("attempts", 1);
        assertThat(db.one("SELECT confidence FROM raw_fact WHERE id=? AND organization_id=?", fact, org)
                        .get("confidence"))
                .isEqualTo(0.9);
        assertThat(db.one("SELECT result FROM llm_enrichment WHERE organization_id=?", org)
                        .get("result")
                        .toString())
                .contains("UNVERIFIED");
        var otherModel = new EnrichmentService(
                db,
                tenant,
                audit,
                access,
                new AiConfiguration("http://127.0.0.1:11434", "other-model", "", "127.0.0.1", 2),
                loader,
                gateway,
                new EnrichmentValidator(
                        Validation.buildDefaultValidatorFactory().getValidator()),
                manager);
        assertThatThrownBy(() -> otherModel.enrich(node))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429");
        assertThat(providerCalls.get()).isEqualTo(1);
        when(tenant.orgId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> loader.node(node))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void runtimeIngestionIsIdempotentImmutableAndOnlyObservesUploadedSpans() {
        String body =
                """
            {"resourceSpans":[{"scopeSpans":[{"spans":[
              {"traceId":"abcdef0123456789abcdef0123456789","spanId":"1234567890abcdef","name":"orders",
               "startTimeUnixNano":"1725000000000000000","endTimeUnixNano":"1725000000000000500",
               "attributes":[{"key":"semantic.stable_key","value":{"stringValue":"rule:order"}}]},
              {"traceId":"abcdef0123456789abcdef0123456789","spanId":"abcdef1234567890","name":"unresolved",
               "startTimeUnixNano":"1725000000000000000","endTimeUnixNano":"1725000000000000500"}
            ]}]}]}
            """;
        var parser = new OtlpParser();
        var spans = parser.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        var runtime = new RuntimeService(db, tenant, access, audit);
        var result = transactions.execute(tx -> runtime.ingest(run, spans));
        assertThat(result)
                .containsEntry("acceptedSpans", 2)
                .containsEntry("matchedSpans", 1)
                .containsEntry("unresolvedSpans", 1);
        var duplicateResult = transactions.execute(tx -> runtime.ingest(run, spans));
        assertThat(duplicateResult).containsEntry("duplicateSpans", 2).containsEntry("acceptedSpans", 0);
        assertThat(db.rows("SELECT span_id FROM runtime_observation WHERE organization_id=?", org))
                .hasSize(1);
        assertThat(runtime.get(run, 100, 0).get("summary").toString())
                .contains("wholeApplicationCoverageKnown=false", "observedNodes=1");
        var conflicting = parser.parse(new ByteArrayInputStream(
                body.replace("\"name\":\"orders\"", "\"name\":\"different\"").getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> transactions.execute(tx -> runtime.ingest(run, conflicting)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        assertThatThrownBy(() -> db.update("UPDATE runtime_span SET name='altered' WHERE organization_id=?", org))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> db.update(
                        "UPDATE runtime_observation SET origin='RUNTIME_OBSERVED' WHERE organization_id=?", org))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        UUID anotherNode = UUID.randomUUID();
        assertThatThrownBy(() -> db.update(
                        "INSERT INTO runtime_observation(span_id,organization_id,project_id,analysis_run_id,node_id) SELECT id,organization_id,project_id,analysis_run_id,? FROM runtime_span WHERE organization_id=? AND matched_node_id IS NULL",
                        anotherNode,
                        org))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(db.one("SELECT confidence FROM semantic_node WHERE id=? AND organization_id=?", node, org)
                        .get("confidence"))
                .isEqualTo(0.9);
    }
}
