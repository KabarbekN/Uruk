package io.semanticmap.analyzer.java;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;
import io.semanticmap.contract.Protocol;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class SpringAnalyzerCli {
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    static final String VERSION = "0.1.0";

    private SpringAnalyzerCli() {}

    public static void main(String[] args) {
        String override = System.getenv("SEMANTIC_WORKSPACE");
        System.exit(run(
                args,
                Path.of(override == null || override.isBlank() ? "/workspace" : override),
                System.out,
                System.err));
    }

    public static int run(String[] args, Path workspace, PrintStream stdout, PrintStream stderr) {
        Instant start = Instant.now();
        Path output = null;
        Protocol.Request request = null;
        try {
            if (args.length == 1 && args[0].equals("describe")) {
                stdout.println(JSON.writeValueAsString(Map.of(
                        "contractVersion",
                        Protocol.VERSION,
                        "id",
                        "java-spring",
                        "version",
                        VERSION,
                        "languages",
                        List.of("JAVA"),
                        "frameworks",
                        List.of("SPRING_BOOT", "SPRING_MVC", "SPRING_SECURITY", "JPA"),
                        "capabilities",
                        AnalysisEngine.LIMITATIONS.keySet(),
                        "executionMode",
                        "SAFE_STATIC",
                        "metadata",
                        Map.of(
                                "sourceModel",
                                "OpenRewrite Java 21",
                                "limitations",
                                AnalysisEngine.LIMITATIONS,
                                "executesBuildScripts",
                                false,
                                "resolvesDependencies",
                                false))));
                return 0;
            }
            Map<String, String> options = options(args);
            output = Path.of(options.get("--output")).toAbsolutePath().normalize();
            Path requestPath = Path.of(options.get("--request"));
            if (Files.size(requestPath) > 1_048_576)
                throw new AnalyzerFailure(20, "INVALID_REQUEST", "Request exceeds 1 MiB");
            JsonNode json = JSON.readTree(Files.readString(requestPath, StandardCharsets.UTF_8));
            try (InputStream schema = SpringAnalyzerCli.class.getResourceAsStream("/schemas/v1/request.schema.json")) {
                if (schema == null)
                    throw new AnalyzerFailure(60, "MISSING_SCHEMA", "Packaged request schema is missing");
                var errors = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                        .getSchema(schema)
                        .validate(json);
                if (!errors.isEmpty())
                    throw new AnalyzerFailure(
                            20,
                            "INVALID_REQUEST",
                            errors.stream()
                                    .map(ValidationMessage::getMessage)
                                    .sorted()
                                    .reduce((a, b) -> a + "; " + b)
                                    .orElse("Invalid request"));
            }
            request = JSON.treeToValue(json, Protocol.Request.class);
            if (request.policy().executeBuildScripts() || request.policy().resolveDependencies())
                throw new AnalyzerFailure(
                        50,
                        "UNSUPPORTED_EXECUTION_POLICY",
                        "This analyzer supports SAFE_STATIC requests with build execution and dependency resolution disabled");
            Path absoluteWorkspace = workspace.toRealPath();
            if (output.startsWith(absoluteWorkspace))
                throw new AnalyzerFailure(
                        50, "OUTPUT_INSIDE_WORKSPACE", "Output must be outside the read-only source workspace");
            final Protocol.Request accepted = request;
            ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "openrewrite-analysis");
                thread.setDaemon(true);
                return thread;
            });
            AnalysisModel model;
            Future<AnalysisModel> future =
                    executor.submit(() -> new AnalysisEngine().analyze(accepted, absoluteWorkspace));
            try {
                model = future.get(request.policy().maxDurationSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException error) {
                future.cancel(true);
                throw new AnalyzerFailure(50, "DURATION_LIMIT", "Analysis exceeded maxDurationSeconds");
            } catch (ExecutionException error) {
                if (error.getCause() instanceof AnalyzerFailure failure) throw failure;
                throw new AnalyzerFailure(
                        40,
                        "ANALYSIS_FAILURE",
                        Objects.toString(
                                error.getCause().getMessage(),
                                error.getCause().getClass().getSimpleName()));
            } finally {
                executor.shutdownNow();
            }
            List<String> completed = new ArrayList<>(), partial = new ArrayList<>(), failed = new ArrayList<>();
            List<Object> coverage = new ArrayList<>();
            for (String capability : request.requestedCapabilities()) {
                List<String> limitations = AnalysisEngine.LIMITATIONS.get(capability);
                String status;
                if (limitations == null) {
                    failed.add(capability);
                    status = "FAILED";
                    limitations = List.of("Capability is not supported");
                    model.diagnostics.add(new Protocol.Diagnostic(
                            "UNSUPPORTED_CAPABILITY",
                            "WARNING",
                            "Unsupported capability " + capability,
                            null,
                            null,
                            Map.of("capability", capability)));
                } else if (!limitations.isEmpty() || model.failed > 0 || model.partialCoverage) {
                    partial.add(capability);
                    status = "PARTIAL";
                } else {
                    completed.add(capability);
                    status = "COMPLETED";
                }
                coverage.add(Map.of("name", capability, "status", status, "limitations", limitations));
            }
            int exit = partial.isEmpty() && failed.isEmpty() && model.failed == 0 && !model.partialCoverage ? 0 : 10;
            Map<String, byte[]> files = outputFiles(
                    model, start, completed, partial, failed, exit == 0 ? "SUCCEEDED" : "PARTIAL", coverage, request);
            long bytes = files.values().stream().mapToLong(b -> b.length).sum();
            if (bytes > request.policy().maxOutputBytes())
                throw new AnalyzerFailure(50, "OUTPUT_LIMIT", "Analysis output exceeds maxOutputBytes");
            write(output, files);
            return exit;
        } catch (Exception error) {
            int exit = error instanceof AnalyzerFailure failure
                    ? failure.exit
                    : error instanceof IOException || error instanceof IllegalArgumentException ? 20 : 60;
            String code = error instanceof AnalyzerFailure failure
                    ? failure.code
                    : exit == 20 ? "INVALID_REQUEST" : "INTERNAL_FAILURE";
            stderr.println(code + ": "
                    + Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
            if (output != null && !output.startsWith(workspace.toAbsolutePath().normalize())) {
                try {
                    AnalysisModel model = new AnalysisModel();
                    model.diagnostics.add(new Protocol.Diagnostic(
                            code,
                            "ERROR",
                            Objects.toString(error.getMessage(), "Analysis failed"),
                            null,
                            null,
                            Map.of("exitCode", exit)));
                    Map<String, byte[]> files = outputFiles(
                            model,
                            start,
                            List.of(),
                            List.of(),
                            request == null ? List.of() : request.requestedCapabilities(),
                            "FAILED",
                            List.of(),
                            request);
                    if (request == null
                            || files.values().stream().mapToLong(b -> b.length).sum()
                                    <= request.policy().maxOutputBytes()) write(output, files);
                } catch (Exception writeError) {
                    stderr.println("FAILED_TO_WRITE_RESULT: " + writeError.getMessage());
                }
            }
            return exit;
        }
    }

    private static Map<String, String> options(String[] args) {
        if (args.length != 5 || !args[0].equals("analyze"))
            throw new AnalyzerFailure(
                    20,
                    "INVALID_ARGUMENTS",
                    "Usage: semantic-analyzer describe | analyze --request /input/request.json --output /output");
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!Set.of("--request", "--output").contains(args[i]) || options.put(args[i], args[i + 1]) != null)
                throw new AnalyzerFailure(20, "INVALID_ARGUMENTS", "Unknown or repeated option");
        }
        if (options.size() != 2)
            throw new AnalyzerFailure(20, "INVALID_ARGUMENTS", "Both --request and --output are required");
        return options;
    }

    private static Map<String, byte[]> outputFiles(
            AnalysisModel model,
            Instant start,
            List<String> complete,
            List<String> partial,
            List<String> failed,
            String status,
            List<Object> coverage,
            Protocol.Request request)
            throws IOException {
        Map<String, Object> analyzer = new TreeMap<>();
        analyzer.put("id", "java-spring");
        analyzer.put("version", VERSION);
        String digest = System.getenv("SEMANTIC_ANALYZER_IMAGE_DIGEST");
        analyzer.put("imageDigest", digest != null && digest.matches("sha256:[0-9a-f]{64}") ? digest : null);
        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("contractVersion", Protocol.VERSION);
        manifest.put("analyzer", analyzer);
        manifest.put("status", status);
        manifest.put("capabilitiesCompleted", complete);
        manifest.put("capabilitiesPartial", partial);
        manifest.put("capabilitiesFailed", failed);
        manifest.put("factCount", model.facts.size());
        manifest.put("diagnosticCount", model.diagnostics.size());
        manifest.put("startedAt", start.toString());
        manifest.put("finishedAt", Instant.now().toString());
        Map<String, Long> kinds = new TreeMap<>();
        model.facts.values().forEach(f -> kinds.merge(f.kind(), 1L, Long::sum));
        Map<String, Object> metadata = new TreeMap<>();
        metadata.put("mode", "SAFE_STATIC");
        metadata.put("incremental", false);
        metadata.put("testsExecuted", false);
        if (request != null) {
            metadata.put("analysisId", request.analysisId());
            metadata.put("revision", request.revision());
        }
        Map<String, byte[]> files = new TreeMap<>();
        files.put("manifest.json", JSON.writeValueAsBytes(manifest));
        files.put("facts.ndjson", ndjson(model.facts.values()));
        files.put("diagnostics.ndjson", ndjson(model.diagnostics));
        files.put(
                "coverage.json",
                JSON.writeValueAsBytes(Map.of(
                        "contractVersion",
                        Protocol.VERSION,
                        "filesDiscovered",
                        model.discovered,
                        "filesParsed",
                        model.units.size(),
                        "filesFailed",
                        model.failed,
                        "capabilities",
                        coverage,
                        "metadata",
                        metadata)));
        files.put(
                "statistics.json",
                JSON.writeValueAsBytes(Map.of(
                        "contractVersion",
                        Protocol.VERSION,
                        "factCount",
                        model.facts.size(),
                        "diagnosticCount",
                        model.diagnostics.size(),
                        "factsByKind",
                        kinds,
                        "metadata",
                        metadata)));
        return files;
    }

    private static byte[] ndjson(Collection<?> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object row : rows) {
            out.write(JSON.writeValueAsBytes(row));
            out.write('\n');
        }
        return out.toByteArray();
    }

    private static void write(Path output, Map<String, byte[]> files) throws IOException {
        Files.createDirectories(output);
        if (Files.isSymbolicLink(output)
                || !output.toRealPath().equals(output.toAbsolutePath().normalize()))
            throw new IOException("Output path must not contain symbolic links");
        for (var file : files.entrySet()) {
            Path destination = output.resolve(file.getKey());
            if (Files.isSymbolicLink(destination)) throw new IOException("Output files must not be symbolic links");
            Path temporary = Files.createTempFile(output, file.getKey(), ".tmp");
            try {
                Files.write(temporary, file.getValue());
                try {
                    Files.move(
                            temporary,
                            destination,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException error) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
