package io.semanticmap.platform.runner;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalyzerOutputValidatorTest {
    @TempDir
    Path root;

    @Test
    void failedExitIsNotMaskedByMissingOutput() {
        assertThatThrownBy(
                        () -> new AnalyzerOutputValidator(new ObjectMapper()).validate(command(), root, digest(), 128))
                .hasMessage("ANALYZER_EXECUTION_FAILED");
    }

    @Test
    void partialCoverageCannotBecomeSuccessfulExecution() throws Exception {
        writeOutput();
        var result = new AnalyzerOutputValidator(new ObjectMapper()).validate(command(), root, digest(), 0);
        assertThat(result.status()).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(result.imageDigest()).isEqualTo(digest());
    }

    @Test
    void mismatchedAnalyzerIdentityIsRejected() throws Exception {
        writeOutput();
        Files.writeString(
                root.resolve("manifest.json"),
                Files.readString(root.resolve("manifest.json")).replace("java-spring", "different"));
        assertThatThrownBy(() -> new AnalyzerOutputValidator(new ObjectMapper()).validate(command(), root, digest(), 0))
                .hasMessage("ANALYZER_IDENTITY_MISMATCH");
    }

    @Test
    void missingRequestedCapabilityIsReportedAsCoverageGap() throws Exception {
        writeOutput();
        Files.writeString(
                root.resolve("coverage.json"),
                "{\"contractVersion\":\"1.0\",\"filesDiscovered\":1,\"filesParsed\":1,\"filesFailed\":0,\"capabilities\":[],\"metadata\":{}}");
        Files.writeString(
                root.resolve("manifest.json"),
                Files.readString(root.resolve("manifest.json")).replace("[\"SYMBOLS\"]", "[]"));
        var result = new AnalyzerOutputValidator(new ObjectMapper()).validate(command(), root, digest(), 0);
        assertThat(result.status()).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(result.diagnostics())
                .extracting(item -> item.get("code"))
                .contains("REQUESTED_CAPABILITY_INCOMPLETE");
    }

    @Test
    void undeclaredManifestFieldsAreRejected() throws Exception {
        writeOutput();
        var mapper = new ObjectMapper();
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode)
                mapper.readTree(root.resolve("manifest.json").toFile());
        manifest.put("unknown", "field");
        mapper.writeValue(root.resolve("manifest.json").toFile(), manifest);
        assertThatThrownBy(() -> new AnalyzerOutputValidator(mapper).validate(command(), root, digest(), 0))
                .hasMessage("INVALID_ANALYZER_MANIFEST");
    }

    private String digest() {
        return "sha256:" + "c".repeat(64);
    }

    private AnalyzerExecutionPort.Command command() {
        return new AnalyzerExecutionPort.Command(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "java-spring",
                "0.1.0",
                "semanticmap/java-spring:0.1.0",
                root,
                root,
                RunnerPolicyTest.request(),
                () -> false);
    }

    private void writeOutput() throws Exception {
        Files.writeString(
                root.resolve("manifest.json"),
                """
                {"contractVersion":"1.0","analyzer":{"id":"java-spring","version":"0.1.0","imageDigest":null},"status":"SUCCEEDED",
                 "capabilitiesCompleted":["SYMBOLS"],"capabilitiesPartial":[],"capabilitiesFailed":[],"factCount":0,"diagnosticCount":0,
                 "startedAt":"2026-09-02T00:00:00Z","finishedAt":"2026-09-02T00:00:01Z"}
                """);
        Files.writeString(
                root.resolve("coverage.json"),
                """
                {"contractVersion":"1.0","filesDiscovered":2,"filesParsed":1,"filesFailed":1,
                 "capabilities":[{"name":"SYMBOLS","status":"PARTIAL","limitations":["Malformed source"]}],"metadata":{}}
                """);
        Files.writeString(
                root.resolve("statistics.json"),
                "{\"contractVersion\":\"1.0\",\"factCount\":0,\"diagnosticCount\":0,\"factsByKind\":{},\"metadata\":{}}");
        Files.writeString(root.resolve("facts.ndjson"), "");
        Files.writeString(root.resolve("diagnostics.ndjson"), "");
    }
}
