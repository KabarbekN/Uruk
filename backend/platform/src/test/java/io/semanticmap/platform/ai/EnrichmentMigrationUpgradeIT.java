package io.semanticmap.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class EnrichmentMigrationUpgradeIT {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

    @Test
    void upgradesTheHistoricalV4WithoutRepairingItsChecksum() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new JdbcTemplate(source);
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .target("4")
                .load()
                .migrate();
        assertThat(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='4'", Integer.class))
                .isEqualTo(-1280182665);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND column_name IN ('estimated_cost_usd','trust_status')",
                        Long.class))
                .isZero();

        var current = Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .load();
        current.migrate();
        current.validate();
        assertThat(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='4'", Integer.class))
                .isEqualTo(-1280182665);
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='7'", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForList(
                        "SELECT table_name || '.' || column_name FROM information_schema.columns WHERE table_schema='public' AND column_name IN ('estimated_cost_usd','trust_status')",
                        String.class))
                .containsExactlyInAnyOrder("llm_execution.estimated_cost_usd", "llm_enrichment.trust_status");
        assertThat(jdbc.queryForObject(
                        "SELECT column_default FROM information_schema.columns WHERE table_schema='public' AND table_name='llm_enrichment' AND column_name='trust_status'",
                        String.class))
                .contains("UNVERIFIED");
    }
}
