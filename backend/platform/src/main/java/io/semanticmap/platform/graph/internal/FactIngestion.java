package io.semanticmap.platform.graph.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.semanticmap.contract.Protocol;
import io.semanticmap.platform.shared.Db;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FactIngestion {
    private final Db db;
    private final ObjectMapper mapper;
    private final JsonSchema schema;
    private final EvidenceVerifier verifier = new EvidenceVerifier();

    public FactIngestion(Db db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
        try (var input = Protocol.class.getResourceAsStream("/schemas/v1/fact.schema.json")) {
            if (input == null) throw new IllegalStateException("Missing analyzer fact schema");
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(input);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot load analyzer fact schema", ex);
        }
    }

    public void ingest(UUID org, UUID project, UUID revision, UUID run, UUID execution, Path workspace, Path output) {
        var provenance = db.one(
                "SELECT e.* FROM analyzer_execution e JOIN analysis_run r ON r.id=e.analysis_run_id AND r.organization_id=e.organization_id AND r.project_id=e.project_id WHERE e.id=? AND e.organization_id=? AND e.project_id=? AND e.revision_id=? AND e.analysis_run_id=? AND r.revision_id=? FOR UPDATE OF r",
                execution,
                org,
                project,
                revision,
                run,
                revision);
        if (!db.rows(
                        "SELECT id FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=? LIMIT 1",
                        org,
                        project,
                        run)
                .isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Analysis graph is already immutable");
        Path facts = Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS) ? output.resolve("facts.ndjson") : output;
        var evidenceSession = verifier.session(workspace);
        long lineNumber = 0;
        try {
            if (Files.isSymbolicLink(facts) || !Files.isRegularFile(facts, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("FACT_OUTPUT_NOT_REGULAR_FILE");
            try (var lines = new BoundedLines(
                    new BufferedInputStream(Files.newInputStream(facts, LinkOption.NOFOLLOW_LINKS)),
                    1024 * 1024,
                    128L * 1024 * 1024)) {
                BoundedLines.Line line;
                while ((line = lines.next()) != null) {
                    lineNumber++;
                    if (lineNumber > 250_000) {
                        quarantine(org, project, revision, run, execution, lineNumber, "FACT_COUNT_LIMIT", "");
                        break;
                    }
                    if (line.text().isBlank() && !line.oversized()) continue;
                    Protocol.Fact fact;
                    List<EvidenceVerifier.Verified> verified = new ArrayList<>();
                    try {
                        if (line.oversized()) throw new IOException("NDJSON_LINE_LIMIT");
                        if (line.invalidUtf8()) throw new IOException("INVALID_UTF8");
                        var json = mapper.readTree(line.text());
                        if (json == null || !schema.validate(json).isEmpty())
                            throw new IOException("SCHEMA_VALIDATION_FAILED");
                        if (json.toString().contains("\\u0000")) throw new IOException("INVALID_NULL_CHARACTER");
                        fact = mapper.treeToValue(json, Protocol.Fact.class);
                        if (!Protocol.VERSION.equals(fact.contractVersion()))
                            throw new IOException("CONTRACT_VERSION_MISMATCH");
                        if (fact.evidence() == null
                                || fact.evidence().isEmpty()
                                || fact.evidence().size() > 32) throw new IOException("EVIDENCE_REQUIRED_OR_LIMIT");
                        if (fact.properties() == null
                                || fact.stableKey() == null
                                || fact.stableKey().isBlank()
                                || fact.factId() == null
                                || fact.kind() == null
                                || fact.origin() == null
                                || !Double.isFinite(fact.confidence())
                                || fact.confidence() < 0
                                || fact.confidence() > 1) throw new IOException("INVALID_FACT_FIELDS");
                        if (fact.stableKey().length() > 512 || fact.factId().length() > 512)
                            throw new IOException("FACT_KEY_LIMIT");
                        for (String key : List.of("sourceKey", "targetKey", "ownerKey"))
                            if (Semantics.text(fact.properties(), key, "").length() > 512)
                                throw new IOException("FACT_KEY_LIMIT");
                        for (var evidence : fact.evidence()) verified.add(evidenceSession.verify(evidence));
                    } catch (IOException | IllegalArgumentException ex) {
                        String reason = ex instanceof com.fasterxml.jackson.core.JacksonException
                                ? "INVALID_JSON"
                                : ex.getMessage();
                        quarantine(
                                org,
                                project,
                                revision,
                                run,
                                execution,
                                lineNumber,
                                reason == null ? "INVALID_FACT" : reason,
                                line.text());
                        continue;
                    }
                    String fingerprint = Semantics.hash(mapper.convertValue(fact, Map.class));
                    var duplicate = db.rows(
                            "SELECT fingerprint FROM raw_fact WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND analyzer_execution_id=? AND fact_id=?",
                            org,
                            project,
                            run,
                            execution,
                            fact.factId());
                    if (!duplicate.isEmpty()) {
                        if (!fingerprint.equals(duplicate.getFirst().get("fingerprint")))
                            quarantine(
                                    org,
                                    project,
                                    revision,
                                    run,
                                    execution,
                                    lineNumber,
                                    "DUPLICATE_ID_CONFLICT",
                                    line.text());
                        continue;
                    }
                    UUID factId = UUID.randomUUID();
                    double confidence = Semantics.confidence(
                            fact.origin(),
                            fact.confidence(),
                            fact.properties(),
                            Semantics.map(provenance.get("coverage")));
                    db.update(
                            "INSERT INTO raw_fact(id,organization_id,project_id,revision_id,analysis_run_id,analyzer_execution_id,fact_id,stable_key,kind,origin,confidence,payload,fingerprint) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)",
                            factId,
                            org,
                            project,
                            revision,
                            run,
                            execution,
                            fact.factId(),
                            fact.stableKey(),
                            fact.kind(),
                            fact.origin(),
                            confidence,
                            db.json(fact),
                            fingerprint);
                    for (var item : verified) {
                        var ev = item.source();
                        db.update(
                                "INSERT INTO evidence(id,organization_id,project_id,analysis_run_id,revision_id,raw_fact_id,analyzer_execution_id,file_path,start_line,end_line,start_column,end_column,snippet,snippet_hash,analyzer_id,analyzer_version,image_digest,origin,verified) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true)",
                                UUID.randomUUID(),
                                org,
                                project,
                                run,
                                revision,
                                factId,
                                execution,
                                ev.filePath().replace('\\', '/'),
                                ev.startLine(),
                                ev.endLine(),
                                ev.startColumn(),
                                ev.endColumn(),
                                item.snippet(),
                                ev.snippetHash(),
                                provenance.get("analyzerKey"),
                                provenance.get("version"),
                                provenance.get("imageDigest"),
                                fact.origin());
                    }
                }
            }
        } catch (IOException ex) {
            quarantine(org, project, revision, run, execution, lineNumber + 1, "OUTPUT_READ_FAILED", "");
        }
    }

    private void quarantine(
            UUID org, UUID project, UUID revision, UUID run, UUID execution, long line, String reason, String raw) {
        db.update(
                "INSERT INTO fact_quarantine(id,organization_id,project_id,revision_id,analysis_run_id,analyzer_execution_id,line_number,reason,raw_line) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (analyzer_execution_id,line_number,reason) DO NOTHING",
                UUID.randomUUID(),
                org,
                project,
                revision,
                run,
                execution,
                line,
                reason.substring(0, Math.min(500, reason.length())),
                raw.substring(0, Math.min(raw.length(), 4096)).replace("\u0000", ""));
    }
}
