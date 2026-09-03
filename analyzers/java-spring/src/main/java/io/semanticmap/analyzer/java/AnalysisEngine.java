package io.semanticmap.analyzer.java;

import io.semanticmap.contract.Protocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

final class AnalysisEngine {
    static final Map<String, List<String>> LIMITATIONS;

    static {
        Map<String, List<String>> limits = new TreeMap<>();
        limits.put("SYMBOLS", List.of());
        limits.put("TYPE_HIERARCHY", List.of("Only source-declared inheritance targets are linked."));
        limits.put(
                "ENDPOINTS", List.of("Dynamic paths, custom composed mappings and router functions are not resolved."));
        limits.put(
                "CALL_GRAPH",
                List.of(
                        "Only unambiguous source method targets are linked. Dependency, reflective and polymorphic dispatch is unresolved."));
        limits.put(
                "VALIDATIONS",
                List.of("Custom validators are partial. Validation groups and runtime activation are not evaluated."));
        limits.put(
                "AUTHORIZATION",
                List.of(
                        "SpEL is parsed, never evaluated. Runtime matcher policies, ownership and tenant proofs are not inferred."));
        limits.put(
                "DATABASE_ACCESS",
                List.of(
                        "ORM declarations are not proof of executed SQL. Naming strategies, property access, embedded mappings and derived predicate binding may be unresolved. Migration defaults and triggers require the PostgreSQL analyzer."));
        limits.put(
                "SIDE_EFFECTS",
                List.of(
                        "Recognizes declared Feign, RestTemplate, WebClient and ApplicationEventPublisher receivers. Dynamic fluent receiver types and custom event abstractions may be unresolved."));
        limits.put(
                "CONDITIONS",
                List.of(
                        "If/ternary/switch expressions and local branch effects only. Loop execution, switch fall-through, exception interception and interprocedural path feasibility are not solved."));
        limits.put(
                "TEST_LINKS",
                List.of(
                        "JUnit source and direct production calls only. Tests are not executed; mocks, endpoint assertions and exception assertions are not treated as full coverage."));
        limits.put(
                "TRANSACTIONS",
                List.of("Annotation boundaries only; proxy activation and self-invocation are not evaluated."));
        limits.put("SCHEDULED_JOBS", List.of("Annotation declarations only; schedule properties are not evaluated."));
        LIMITATIONS = Collections.unmodifiableMap(limits);
    }

    AnalysisModel analyze(Protocol.Request request, Path workspace) throws IOException {
        Path root = workspace.toRealPath();
        Path component = safeRelative(root, request.component().rootPath());
        if (!Files.isDirectory(component))
            throw new AnalyzerFailure(30, "UNSUPPORTED_COMPONENT", "Component directory does not exist");
        if (!component.toRealPath().startsWith(root))
            throw new AnalyzerFailure(50, "PATH_ESCAPE", "Component escapes the workspace");
        if (!request.component().languages().contains("JAVA"))
            throw new AnalyzerFailure(30, "UNSUPPORTED_LANGUAGE", "Java is not requested");
        for (String changed : request.changedFiles()) safeRelative(root, changed);
        AnalysisModel model = new AnalysisModel();
        Map<String, String> sources = new TreeMap<>();
        try (var paths = Files.walk(component)) {
            long totalBytes = 0;
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (excluded(relative, request.policy())) continue;
                model.discovered++;
                if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root)) {
                    model.failed++;
                    model.diagnostics.add(new Protocol.Diagnostic(
                            "SOURCE_PATH_ESCAPE",
                            "ERROR",
                            "Source link is outside the allowed workspace",
                            relative,
                            null,
                            Map.of()));
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                long length = Files.size(path);
                totalBytes += length;
                if (length > 2_097_152 || totalBytes > 67_108_864 || model.discovered > 5000)
                    throw new AnalyzerFailure(
                            50,
                            "SOURCE_LIMIT",
                            "Static source input limit exceeded (2 MiB/file, 64 MiB total, 5000 files)");
                sources.put(relative, Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        if (sources.isEmpty()) throw new AnalyzerFailure(30, "NO_JAVA_SOURCES", "No supported Java source files found");
        List<Parser.Input> inputs = sources.entrySet().stream()
                .map(e -> Parser.Input.fromString(root.resolve(e.getKey()), e.getValue()))
                .toList();
        var context = new InMemoryExecutionContext(error -> model.diagnostics.add(new Protocol.Diagnostic(
                "PARSER_DIAGNOSTIC",
                "WARNING",
                Objects.toString(error.getMessage(), error.getClass().getSimpleName()),
                null,
                null,
                Map.of())));
        JavaParser parser = JavaParser.fromJavaVersion()
                .classpath(List.<Path>of())
                .logCompilationWarningsAndErrors(false)
                .build();
        try (var parsed = parser.parseInputs(inputs, root, context)) {
            parsed.forEach(source -> {
                String path = source.getSourcePath().toString().replace('\\', '/');
                if (source instanceof J.CompilationUnit compilation) {
                    try {
                        model.units.add(new SourceUnit(path, sources.get(path), compilation));
                    } catch (RuntimeException error) {
                        model.failed++;
                        model.diagnostics.add(new Protocol.Diagnostic(
                                "SOURCE_RANGE_FAILURE", "ERROR", error.getMessage(), path, null, Map.of()));
                    }
                } else {
                    model.failed++;
                    model.diagnostics.add(new Protocol.Diagnostic(
                            "JAVA_PARSE_ERROR",
                            "ERROR",
                            "OpenRewrite could not parse this source",
                            path,
                            null,
                            Map.of()));
                }
            });
        }
        if (model.units.isEmpty())
            throw new AnalyzerFailure(40, "ALL_SOURCES_FAILED", "No Java sources could be parsed");
        model.units.sort(Comparator.comparing(u -> u.path));
        new SymbolExtractor().extract(model);
        new ConstantResolver().extract(model);
        new ConstantResolver().extract(model);
        new TypeHierarchyExtractor().extract(model);
        // Shared mapping context is built before operations and branch-effect classification.
        new JpaMappingExtractor().extract(model);
        Set<String> capabilities = new HashSet<>(request.requestedCapabilities());
        if (capabilities.contains("ENDPOINTS")) new SpringEndpointExtractor().extract(model);
        if (capabilities.contains("VALIDATIONS")) new ValidationExtractor().extract(model);
        if (capabilities.contains("AUTHORIZATION")) new AuthorizationExtractor().extract(model);
        if (capabilities.contains("DATABASE_ACCESS")) new RepositoryQueryExtractor().extract(model);
        if (capabilities.contains("TRANSACTIONS") || capabilities.contains("DATABASE_ACCESS"))
            new TransactionExtractor().extract(model);
        if (capabilities.contains("SCHEDULED_JOBS")) new ScheduledJobExtractor().extract(model);
        if (capabilities.contains("CONDITIONS") || capabilities.contains("CALL_GRAPH")) {
            new NormalizedConditionExtractor().extract(model);
            new ExceptionFlowExtractor().extract(model);
        }
        if (capabilities.contains("CALL_GRAPH") || capabilities.contains("DATABASE_ACCESS"))
            new MethodCallExtractor().extract(model);
        if (capabilities.contains("SIDE_EFFECTS")) new ExternalCallExtractor().extract(model);
        if (capabilities.contains("TEST_LINKS") && request.policy().includeTests())
            new TestLinkExtractor().extract(model);
        model.diagnostics.sort(Comparator.comparing((Protocol.Diagnostic d) -> Objects.toString(d.filePath(), ""))
                .thenComparing(d -> d.startLine() == null ? 0 : d.startLine())
                .thenComparing(Protocol.Diagnostic::code)
                .thenComparing(Protocol.Diagnostic::message));
        return model;
    }

    static Path safeRelative(Path root, String value) {
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/")
                || normalized.matches("^[A-Za-z]:.*")
                || Arrays.asList(normalized.split("/")).contains(".."))
            throw new AnalyzerFailure(50, "PATH_ESCAPE", "Paths must be repository-relative without traversal");
        Path resolved = root.resolve(normalized.isEmpty() ? "." : normalized).normalize();
        if (!resolved.startsWith(root)) throw new AnalyzerFailure(50, "PATH_ESCAPE", "Path escapes the workspace");
        return resolved;
    }

    private static boolean excluded(String path, Protocol.Policy policy) {
        List<String> parts = Arrays.asList(path.split("/"));
        if (parts.stream().anyMatch(p -> Set.of(".git", ".gradle", ".idea", "node_modules", ".mvn")
                .contains(p))) return true;
        if (!policy.includeTests() && (parts.contains("test") || parts.contains("tests"))) return true;
        return !policy.includeGeneratedSources()
                && parts.stream().anyMatch(p -> Set.of("target", "build", "generated", "generated-sources")
                        .contains(p));
    }
}

final class AnalyzerFailure extends RuntimeException {
    final int exit;
    final String code;

    AnalyzerFailure(int exit, String code, String message) {
        super(message);
        this.exit = exit;
        this.code = code;
    }
}
