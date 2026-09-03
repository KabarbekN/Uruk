package io.semanticmap.platform.graph.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.semanticmap.contract.Protocol;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceVerificationSessionTest {
    @TempDir
    Path root;

    private final EvidenceVerifier verifier = spy(new EvidenceVerifier());

    @Test
    void equalRecordsReuseOneSuccessfulSourceRead() throws Exception {
        Files.writeString(root.resolve("source"), "abc\r\nlast\r\n");
        var session = verifier.session(root);
        var original = evidence("source");
        var result = session.verify(original);

        assertThat(result.snippet()).isEqualTo("abc");
        assertThat(session.verify(evidence("source"))).isEqualTo(result);
        verify(verifier, times(1)).openSource(root.resolve("source"));
        verify(verifier, times(1)).resolveSource(root, original);
    }

    @Test
    void sourceIsSharedButHashAndEveryRangeCoordinateAreStillValidated() throws Exception {
        Files.writeString(root.resolve("source"), "abc\nabc\n");
        Files.writeString(root.resolve("other"), "abc\n");
        var session = verifier.session(root);
        session.verify(evidence("source"));

        var changedHash = new Protocol.Evidence("source", 1, 1, 1, 4, Protocol.hash("wrong"));
        var changedStartLine = new Protocol.Evidence("source", 2, 1, 1, 4, Protocol.hash("abc"));
        var changedEndLine = new Protocol.Evidence("source", 1, 1, 2, 4, Protocol.hash("abc"));
        var changedStartColumn = new Protocol.Evidence("source", 1, 2, 1, 4, Protocol.hash("abc"));
        var changedEndColumn = new Protocol.Evidence("source", 1, 1, 1, 3, Protocol.hash("abc"));
        assertThatThrownBy(() -> session.verify(changedHash))
                .isInstanceOf(IOException.class)
                .hasMessage("SNIPPET_HASH_MISMATCH");
        assertThatThrownBy(() -> session.verify(changedStartLine))
                .isInstanceOf(IOException.class)
                .hasMessage("INVALID_RANGE");
        assertThatThrownBy(() -> session.verify(changedEndLine))
                .isInstanceOf(IOException.class)
                .hasMessage("SNIPPET_HASH_MISMATCH");
        assertThat(session.verify(changedStartColumn).snippet()).isEqualTo("abc");
        assertThat(session.verify(changedEndColumn).snippet()).isEqualTo("abc");
        assertThat(session.verify(new Protocol.Evidence("source", 2, 1, 2, 4, Protocol.hash("abc")))
                        .snippet())
                .isEqualTo("abc");
        assertThat(session.verify(evidence("other")).snippet()).isEqualTo("abc");
        verify(verifier, times(1)).openSource(root.resolve("source"));
        verify(verifier, times(1)).openSource(root.resolve("other"));
    }

    @Test
    void failuresAreRecheckedAndNeverCached() throws Exception {
        Files.writeString(root.resolve("source"), "wrong\n");
        var session = verifier.session(root);
        var evidence = evidence("source");
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> session.verify(evidence))
                    .isInstanceOf(IOException.class)
                    .hasMessage("SNIPPET_HASH_MISMATCH");
        }
        Files.writeString(root.resolve("source"), "abc\n");
        assertThat(session.verify(evidence).snippet()).isEqualTo("abc");
        session.verify(evidence);
        verify(verifier, times(3)).openSource(root.resolve("source"));
    }

    @Test
    void sessionsDoNotShareResultsAcrossWorkspacesOrLaterIngestions() throws Exception {
        Files.writeString(root.resolve("source"), "abc\n");
        var evidence = evidence("source");
        verifier.session(root).verify(evidence);

        Files.writeString(root.resolve("source"), "changed\n");
        assertThatThrownBy(() -> verifier.session(root).verify(evidence))
                .isInstanceOf(IOException.class)
                .hasMessage("SNIPPET_HASH_MISMATCH");
        Path other = Files.createDirectory(root.resolve("different-workspace"));
        Files.writeString(other.resolve("source"), "different\n");
        assertThatThrownBy(() -> verifier.session(other).verify(evidence))
                .isInstanceOf(IOException.class)
                .hasMessage("SNIPPET_HASH_MISMATCH");
        verify(verifier, times(2)).openSource(root.resolve("source"));
        verify(verifier).openSource(other.resolve("source"));
    }

    @Test
    void cachedValidEvidenceDoesNotBypassPathChecksOnMisses() throws Exception {
        Files.writeString(root.resolve("source"), "abc\n");
        var session = verifier.session(root);
        session.verify(evidence("source"));
        assertThatThrownBy(() -> session.verify(evidence("unused/../source")))
                .isInstanceOf(IOException.class)
                .hasMessage("PATH_ESCAPE");
        assertThatThrownBy(() -> session.verify(evidence("missing")))
                .isInstanceOf(IOException.class)
                .hasMessage("SOURCE_NOT_REGULAR_FILE");
    }

    @Test
    void entryLimitEvictsLeastRecentlyUsedResultAt1024Entries() throws Exception {
        stubSourceReads("abc\n");
        var session = verifier.session(root);
        for (int index = 0; index < 1024; index++) session.verify(evidence("file-" + index));
        session.verify(evidence("file-0"));
        session.verify(evidence("file-1024"));
        session.verify(evidence("file-0"));
        session.verify(evidence("file-1"));

        verify(verifier, times(1)).openSource(root.resolve("file-0"));
        verify(verifier, times(2)).openSource(root.resolve("file-1"));
        verify(verifier, times(1026)).openSource(any());
    }

    @Test
    void byteBudgetEvictsLargeSnippetsBeforeEntryLimit() throws Exception {
        stubSourceReads("abc\n" + "x".repeat(64 * 1024 - 1) + "\n");
        var session = verifier.session(root);
        for (int index = 0; index < 64; index++) session.verify(evidence("file-" + index));
        session.verify(evidence("file-63"));
        session.verify(evidence("file-0"));

        verify(verifier, times(1)).openSource(root.resolve("file-63"));
        verify(verifier, times(2)).openSource(root.resolve("file-0"));
        verify(verifier, times(65)).openSource(any());
    }

    @Test
    void malformedSuffixDoesNotInvalidateValidPrefixEvidence() throws Exception {
        Files.write(root.resolve("source"), new byte[] {'a', 'b', 'c', '\n', (byte) 0xc3, 0x28, '\n'});
        var session = verifier.session(root);
        assertThat(session.verify(evidence("source")).snippet()).isEqualTo("abc");
        assertThatThrownBy(() -> session.verify(new Protocol.Evidence("source", 2, 1, 2, 1, Protocol.hash(""))))
                .isInstanceOf(IOException.class)
                .hasMessage("SOURCE_ENCODING_OR_LINE_LIMIT");
    }

    @Test
    void oversizedSourceFallsBackWithoutLosingValidPrefixOrStreamLimit() throws Exception {
        stubSourceReads("abc\n" + ("x".repeat(8191) + "\n").repeat(2049));
        var session = verifier.session(root);
        assertThat(session.verify(evidence("file")).snippet()).isEqualTo("abc");
        assertThatThrownBy(() -> session.verify(new Protocol.Evidence("file", 2050, 1, 2050, 1, Protocol.hash(""))))
                .isInstanceOf(IOException.class)
                .hasMessage("STREAM_BYTE_LIMIT");
        verify(verifier, times(4)).openSource(root.resolve("file"));
    }

    @Test
    void cacheHitsStillEnforceSnippetAndLineRangeLimits() throws Exception {
        Files.writeString(root.resolve("source"), "abc\n" + ("x".repeat(40_000) + "\n").repeat(2));
        var session = verifier.session(root);
        session.verify(evidence("source"));
        assertThatThrownBy(() -> session.verify(new Protocol.Evidence("source", 2, 1, 3, 1, Protocol.hash(""))))
                .isInstanceOf(IOException.class)
                .hasMessage("SNIPPET_LIMIT");
        assertThatThrownBy(() -> session.verify(new Protocol.Evidence("source", 2, 1, 5, 1, Protocol.hash(""))))
                .isInstanceOf(IOException.class)
                .hasMessage("SNIPPET_LIMIT");
        assertThatThrownBy(() -> session.verify(new Protocol.Evidence("source", 1, 1, 2002, 1, Protocol.hash(""))))
                .isInstanceOf(IOException.class)
                .hasMessage("INVALID_RANGE");
        verify(verifier, times(1)).openSource(root.resolve("source"));
    }

    @Test
    void firstLoadRejectsSymlinkEvenAfterTargetWasCached() throws Exception {
        Files.writeString(root.resolve("source"), "abc\n");
        try {
            Files.createSymbolicLink(root.resolve("alias"), root.resolve("source"));
        } catch (IOException | UnsupportedOperationException ex) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Host does not permit symlink creation");
        }
        var session = verifier.session(root);
        session.verify(evidence("source"));
        assertThatThrownBy(() -> session.verify(evidence("alias")))
                .isInstanceOf(IOException.class)
                .hasMessage("SYMLINK_REJECTED");
    }

    private void stubSourceReads(String source) throws IOException {
        byte[] content = source.getBytes(StandardCharsets.UTF_8);
        doAnswer(call -> root.resolve(
                        call.getArgument(1, Protocol.Evidence.class).filePath()))
                .when(verifier)
                .resolveSource(eq(root), any());
        doAnswer(call -> new BoundedLines(new ByteArrayInputStream(content), 64 * 1024, 16L * 1024 * 1024))
                .when(verifier)
                .openSource(any());
    }

    private static Protocol.Evidence evidence(String file) {
        return new Protocol.Evidence(file, 1, 1, 1, 4, Protocol.hash("abc"));
    }
}
