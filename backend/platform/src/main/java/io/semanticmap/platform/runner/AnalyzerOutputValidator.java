package io.semanticmap.platform.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.semanticmap.contract.Protocol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AnalyzerOutputValidator {
    private final ObjectMapper mapper;
    private final Map<String, JsonSchema> schemas = new HashMap<>();

    public AnalyzerOutputValidator(ObjectMapper mapper) {
        this.mapper = mapper;
        for (String name : List.of("manifest", "coverage", "diagnostic", "statistics")) {
            try (var input = Protocol.class.getResourceAsStream("/schemas/v1/" + name + ".schema.json")) {
                if (input == null) throw new IllegalStateException("Missing analyzer " + name + " schema");
                schemas.put(
                        name,
                        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                                .getSchema(input));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load analyzer schema", e);
            }
        }
    }

    public AnalyzerExecutionPort.Result validate(
            AnalyzerExecutionPort.Command command, Path output, String digest, int exitCode) throws IOException {
        if (exitCode != 0 && exitCode != 10) throw new IOException("ANALYZER_EXECUTION_FAILED");
        for (String name :
                Set.of("manifest.json", "facts.ndjson", "diagnostics.ndjson", "coverage.json", "statistics.json")) {
            Path file = output.resolve(name);
            RunnerPolicy.rejectLinks(file);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("MISSING_ANALYZER_OUTPUT");
        }
        JsonNode manifest = json(output.resolve("manifest.json"), "manifest");
        JsonNode coverage = json(output.resolve("coverage.json"), "coverage");
        json(output.resolve("statistics.json"), "statistics");
        if (!manifest.path("analyzer").path("id").asText().equals(command.analyzerKey())
                || !manifest.path("analyzer").path("version").asText().equals(command.version()))
            throw new IOException("ANALYZER_IDENTITY_MISMATCH");
        JsonNode statedDigest = manifest.path("analyzer").path("imageDigest");
        if (!statedDigest.isNull() && !statedDigest.asText().equals(digest))
            throw new IOException("ANALYZER_DIGEST_MISMATCH");
        String stated = manifest.path("status").asText();
        if (stated.equals("FAILED")) throw new IOException("ANALYZER_EXECUTION_FAILED");
        boolean partial = exitCode == 10
                || stated.equals("PARTIAL")
                || manifest.path("capabilitiesPartial").size() > 0
                || manifest.path("capabilitiesFailed").size() > 0
                || coverage.path("filesFailed").asLong() > 0;
        long discovered = coverage.path("filesDiscovered").asLong(),
                parsed = coverage.path("filesParsed").asLong(),
                failed = coverage.path("filesFailed").asLong();
        if (parsed > discovered || failed > discovered || parsed + failed > discovered)
            throw new IOException("INCONSISTENT_ANALYZER_COVERAGE");
        if (parsed < discovered) partial = true;
        for (var capability : coverage.path("capabilities"))
            if (!capability.path("status").asText().equals("COMPLETED")) partial = true;
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        var completed = new java.util.HashSet<String>();
        manifest.path("capabilitiesCompleted").forEach(capability -> completed.add(capability.asText()));
        for (String capability : command.request().requestedCapabilities()) {
            if (!completed.contains(capability)) {
                partial = true;
                diagnostics.add(Map.of(
                        "code",
                        "REQUESTED_CAPABILITY_INCOMPLETE",
                        "severity",
                        "WARNING",
                        "message",
                        "Requested capability was not completed: " + capability,
                        "metadata",
                        Map.of("capability", capability)));
            }
        }
        boolean hasErrorOrFatal = false;
        try (var reader = Files.newBufferedReader(output.resolve("diagnostics.ndjson"))) {
            int count = 0;
            StringBuilder line = new StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1) {
                if (ch != '\n') {
                    line.append((char) ch);
                    if (line.length() > 65536) throw new IOException("DIAGNOSTIC_LINE_LIMIT");
                    continue;
                }
                if (!line.isEmpty()) {
                    Map<String, Object> diag = diagnostic(line.toString());
                    String severity = String.valueOf(diag.get("severity"));
                    if ("ERROR".equals(severity) || "FATAL".equals(severity)) {
                        hasErrorOrFatal = true;
                    }
                    if (diagnostics.size() < 2000) {
                        diagnostics.add(diag);
                    }
                    count++;
                }
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                Map<String, Object> diag = diagnostic(line.toString());
                String severity = String.valueOf(diag.get("severity"));
                if ("ERROR".equals(severity) || "FATAL".equals(severity)) {
                    hasErrorOrFatal = true;
                }
                if (diagnostics.size() < 2000) {
                    diagnostics.add(diag);
                }
                count++;
            }
        }
        if (hasErrorOrFatal
                || diagnostics.stream().anyMatch(d -> Set.of("ERROR", "FATAL").contains(d.get("severity"))))
            partial = true;
        return new AnalyzerExecutionPort.Result(
                partial ? "PARTIALLY_SUCCEEDED" : "SUCCEEDED",
                digest,
                output,
                asMap(coverage),
                List.copyOf(diagnostics));
    }

    private Map<String, Object> diagnostic(String line) throws IOException {
        JsonNode node = mapper.readTree(line);
        if (node == null || !schemas.get("diagnostic").validate(node).isEmpty())
            throw new IOException("INVALID_ANALYZER_DIAGNOSTIC");
        return asMap(node);
    }

    private JsonNode json(Path path, String schema) throws IOException {
        if (Files.size(path) > 1048576) throw new IOException("ANALYZER_METADATA_LIMIT");
        JsonNode node = mapper.readTree(Files.readAllBytes(path));
        if (node == null || !schemas.get(schema).validate(node).isEmpty())
            throw new IOException("INVALID_ANALYZER_" + schema.toUpperCase(java.util.Locale.ROOT));
        return node;
    }

    private Map<String, Object> asMap(JsonNode node) {
        return mapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }
}
