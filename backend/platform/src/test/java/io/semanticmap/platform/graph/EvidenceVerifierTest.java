package io.semanticmap.platform.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.semanticmap.contract.Protocol;
import io.semanticmap.platform.graph.internal.EvidenceVerifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceVerifierTest {
    @TempDir
    Path root;

    private final EvidenceVerifier verifier = new EvidenceVerifier();

    @Test
    void hashesFullLinesWithLfAndNoTrailingNewline() throws Exception {
        Files.writeString(root.resolve("Order.java"), "first\r\n  total < 500;\r\nlast\r\n");
        var evidence = new Protocol.Evidence("Order.java", 2, 3, 3, 5, Protocol.hash("  total < 500;\nlast"));
        assertThat(verifier.verify(root, evidence).snippet()).isEqualTo("  total < 500;\nlast");
    }

    @Test
    void rejectsHashOfCroppedColumns() throws Exception {
        Files.writeString(root.resolve("a.java"), "  total < 500;\n");
        assertThatThrownBy(() ->
                        verifier.verify(root, new Protocol.Evidence("a.java", 1, 3, 1, 8, Protocol.hash("total"))))
                .isInstanceOf(IOException.class)
                .hasMessage("SNIPPET_HASH_MISMATCH");
    }

    @Test
    void rejectsTraversalAndDrivePaths() {
        for (String path : new String[] {"../secret", "x/../a", "..\\secret", "C:/secret", "/secret", "a:stream"})
            assertThatThrownBy(() -> verifier.verify(root, new Protocol.Evidence(path, 1, 1, 1, 1, Protocol.hash(""))))
                    .isInstanceOf(IOException.class)
                    .hasMessage("PATH_ESCAPE");
    }

    @Test
    void rejectsMissingAndReversedRangesAndColumns() throws Exception {
        Files.writeString(root.resolve("a"), "abc\n");
        for (var evidence : new Protocol.Evidence[] {
            new Protocol.Evidence("a", 2, 1, 2, 1, Protocol.hash("")),
            new Protocol.Evidence("a", 2, 1, 1, 1, Protocol.hash("abc")),
            new Protocol.Evidence("a", 1, 6, 1, 7, Protocol.hash("abc")),
            new Protocol.Evidence("a", 1, 3, 1, 2, Protocol.hash("abc"))
        }) assertThatThrownBy(() -> verifier.verify(root, evidence)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsSymlinkEvenWhenItsTargetIsInsideWorkspace() throws Exception {
        Files.writeString(root.resolve("source"), "abc");
        try {
            Files.createSymbolicLink(root.resolve("alias"), root.resolve("source"));
        } catch (IOException | UnsupportedOperationException ex) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    false,
                    "Host does not permit symlink creation: " + ex.getClass().getSimpleName());
        }
        assertThatThrownBy(
                        () -> verifier.verify(root, new Protocol.Evidence("alias", 1, 1, 1, 4, Protocol.hash("abc"))))
                .isInstanceOf(IOException.class)
                .hasMessage("SYMLINK_REJECTED");
    }
}
