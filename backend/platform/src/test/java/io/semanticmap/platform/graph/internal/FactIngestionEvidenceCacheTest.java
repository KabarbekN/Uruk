package io.semanticmap.platform.graph.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.semanticmap.contract.Protocol;
import io.semanticmap.platform.shared.Db;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FactIngestionEvidenceCacheTest {
    @TempDir
    Path root;

    private final Db db = mock(Db.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID org = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID revision = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();
    private final UUID execution = UUID.randomUUID();
    private final Protocol.Evidence evidence = new Protocol.Evidence("source", 1, 1, 1, 4, Protocol.hash("abc"));

    @BeforeEach
    void setup() throws Exception {
        Files.writeString(root.resolve("source"), "abc\n");
        when(db.one(anyString(), any(Object[].class)))
                .thenReturn(Map.of("analyzerKey", "java-spring", "version", "0.1.0", "coverage", Map.of()));
        when(db.rows(anyString(), any(Object[].class))).thenReturn(List.of());
        when(db.json(any())).thenAnswer(call -> mapper.writeValueAsString(call.getArgument(0)));
    }

    @Test
    void identicalEvidenceAndDifferentColumnsShareOneReadButRetainSeparateRows() throws Exception {
        var differentColumns = new Protocol.Evidence("source", 1, 2, 1, 3, evidence.snippetHash());
        writeFacts(fact("first", evidence), fact("second", evidence), fact("columns", differentColumns));
        try (var constructed =
                mockConstruction(EvidenceVerifier.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            ingest(new FactIngestion(db, mapper));
            verify(constructed.constructed().getFirst(), times(1)).openSource(root.resolve("source"));
        }
        assertThat(writes("raw_fact")).hasSize(3);
        var evidenceRows = writes("evidence");
        assertThat(evidenceRows).hasSize(3);
        assertThat(evidenceRows.stream().map(row -> row[0]).distinct().toList()).hasSize(3);
        assertThat(evidenceRows.stream().map(row -> row[5]).distinct().toList()).hasSize(3);
        assertThat(evidenceRows.getLast()[10]).isEqualTo(2);
        assertThat(evidenceRows.getLast()[11]).isEqualTo(3);
        assertThat(evidenceRows).allSatisfy(row -> {
            assertThat(row[12]).isEqualTo("abc");
            assertThat(row[13]).isEqualTo(evidence.snippetHash());
        });
        assertThat(writes("fact_quarantine")).isEmpty();
    }

    @Test
    void anotherIngestionOfSameRunAndWorkspaceReverifiesSource() throws Exception {
        writeFacts(fact("first", evidence));
        try (var constructed =
                mockConstruction(EvidenceVerifier.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            var ingestion = new FactIngestion(db, mapper);
            ingest(ingestion);
            Files.writeString(root.resolve("source"), "changed\n");
            ingest(ingestion);
            verify(constructed.constructed().getFirst(), times(2)).openSource(root.resolve("source"));
        }
        assertThat(writes("raw_fact")).hasSize(1);
        assertThat(writes("evidence")).hasSize(1);
        assertThat(writes("fact_quarantine")).singleElement().satisfies(row -> assertThat(row[7])
                .isEqualTo("SNIPPET_HASH_MISMATCH"));
    }

    @Test
    void changedHashAndRangeAreQuarantinedWhileValidPartialFactsSurvive() throws Exception {
        var badHash = new Protocol.Evidence("source", 1, 1, 1, 4, Protocol.hash("wrong"));
        var badColumn = new Protocol.Evidence("source", 1, 1, 1, 99, evidence.snippetHash());
        writeFacts(fact("first", evidence), fact("hash", badHash), fact("range", badColumn), fact("last", evidence));
        try (var constructed =
                mockConstruction(EvidenceVerifier.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            ingest(new FactIngestion(db, mapper));
            var verifier = constructed.constructed().getFirst();
            verify(verifier, times(1)).openSource(root.resolve("source"));
        }
        assertThat(writes("raw_fact")).hasSize(2);
        assertThat(writes("evidence")).hasSize(2);
        assertThat(writes("fact_quarantine").stream().map(row -> row[7]).toList())
                .containsExactly("SNIPPET_HASH_MISMATCH", "INVALID_COLUMN_RANGE");
    }

    private void ingest(FactIngestion ingestion) {
        ingestion.ingest(org, project, revision, run, execution, root, root.resolve("facts.ndjson"));
    }

    private Protocol.Fact fact(String id, Protocol.Evidence proof) {
        return new Protocol.Fact(
                "1.0",
                id,
                "METHOD",
                id,
                new Protocol.Subject("METHOD", id),
                Map.of("name", id),
                "STATIC_EXACT",
                1,
                List.of(proof));
    }

    private void writeFacts(Protocol.Fact... facts) throws Exception {
        var lines = new java.util.ArrayList<String>();
        for (var fact : facts) lines.add(mapper.writeValueAsString(fact));
        Files.write(root.resolve("facts.ndjson"), lines);
    }

    private List<Object[]> writes(String table) {
        return mockingDetails(db).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("update"))
                .filter(call -> call.getArgument(0, String.class).startsWith("INSERT INTO " + table + "("))
                .map(call -> (Object[]) call.getRawArguments()[1])
                .toList();
    }
}
