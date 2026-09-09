package io.semanticmap.analyzer.java;

import static org.junit.jupiter.api.Assertions.*;

import io.semanticmap.contract.Protocol;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceDiscoveryTest {
    @TempDir
    Path root;

    private Protocol.Policy policy(boolean generated, boolean tests) {
        return new Protocol.Policy("DENY", false, false, tests, generated, 60, 8_388_608);
    }

    private void source(String path) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Example {}");
    }

    @Test
    void prunesExcludedDirectoriesBeforeVisitingTheirContents() throws IOException {
        source("Keep.java");
        source("node_modules/a/b/c/Hidden.java");
        source(".git/a/b/c/Hidden.java");
        source("build/a/b/c/Hidden.java");
        source("tests/a/b/c/Hidden.java");
        AnalysisModel model = new AnalysisModel();
        Map<String, String> files = new SourceDiscovery(5, 1).read(root, root, policy(false, false), model);
        assertEquals(Set.of("Keep.java"), files.keySet());
        assertEquals(1, model.discovered);
    }

    @Test
    void matchesRootIgnorePatternsAndNegationWithinTheSelectedComponent() throws IOException {
        source("component/Keep.java");
        source("component/Skip.java");
        source("component/nested/Skip.java");
        source("component/nested/Other.java");
        source("component/ignored/deep/Hidden.java");
        source("outside/Outside.java");
        Files.writeString(
                root.resolve(".semanticmapignore"),
                "\uFEFF# Rules are repository-relative\n*.java\n!component/Keep.java\n!component/**/Other.java\ncomponent/ignored/\n");
        var files =
                new SourceDiscovery().read(root, root.resolve("component"), policy(false, true), new AnalysisModel());
        assertEquals(Set.of("component/Keep.java", "component/nested/Other.java"), files.keySet());
    }

    @Test
    void generatedAndTestSourcesRequireTheirRespectivePolicyFlags() throws IOException {
        source("build/Generated.java");
        source("tests/Test.java");
        assertEquals(
                Set.of("build/Generated.java"),
                new SourceDiscovery()
                        .read(root, root, policy(true, false), new AnalysisModel())
                        .keySet());
        assertEquals(
                Set.of("tests/Test.java"),
                new SourceDiscovery()
                        .read(root, root, policy(false, true), new AnalysisModel())
                        .keySet());
    }

    @Test
    void componentSelectionCannotReopenAnIgnoredAncestorDirectory() throws IOException {
        source("hidden/nested/Keep.java");
        Files.writeString(root.resolve(".semanticmapignore"), "hidden/\n!hidden/nested/Keep.java\n");
        AnalysisModel model = new AnalysisModel();
        assertTrue(new SourceDiscovery()
                .read(root, root.resolve("hidden/nested"), policy(false, true), model)
                .isEmpty());
        assertEquals(0, model.discovered);
    }

    @Test
    void enforcesEntryAndDepthBudgetsEvenWithoutJavaFiles() throws IOException {
        Files.createDirectories(root.resolve("a/b/c"));
        AnalyzerFailure depth = assertThrows(AnalyzerFailure.class, () -> new SourceDiscovery(100, 1)
                .read(root, root, policy(false, true), new AnalysisModel()));
        assertEquals("DEPTH_LIMIT", depth.code);
        AnalyzerFailure entries = assertThrows(AnalyzerFailure.class, () -> new SourceDiscovery(1, 64)
                .read(root, root, policy(false, true), new AnalysisModel()));
        assertEquals("ENTRY_LIMIT", entries.code);
    }

    @Test
    void malformedAndBinarySourcesRemainDiagnosedAlongsideValidSources() throws IOException {
        source("Good.java");
        Files.write(root.resolve("Broken.java"), new byte[] {(byte) 0xff});
        Files.writeString(root.resolve("Binary.java"), "class Binary {}\0");
        AnalysisModel model = new AnalysisModel();
        assertEquals(
                Set.of("Good.java"),
                new SourceDiscovery()
                        .read(root, root, policy(false, true), model)
                        .keySet());
        assertEquals(3, model.discovered);
        assertEquals(2, model.failed);
        assertTrue(model.diagnostics.stream().allMatch(item -> item.code().equals("INVALID_ENCODING")));
    }

    @Test
    void rejectsOversizedIgnoreFilesAndHonorsCancellation() throws IOException {
        Files.writeString(root.resolve(".semanticmapignore"), "a".repeat(65_537));
        AnalyzerFailure size = assertThrows(AnalyzerFailure.class, () -> new SourceDiscovery()
                .read(root, root, policy(false, true), new AnalysisModel()));
        assertEquals("IGNORE_LIMIT", size.code);
        Files.delete(root.resolve(".semanticmapignore"));
        Thread.currentThread().interrupt();
        try {
            AnalyzerFailure cancelled = assertThrows(AnalyzerFailure.class, () -> new SourceDiscovery()
                    .read(root, root, policy(false, true), new AnalysisModel()));
            assertEquals("DURATION_LIMIT", cancelled.code);
        } finally {
            Thread.interrupted();
        }
    }
}
