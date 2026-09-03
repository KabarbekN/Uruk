package io.semanticmap.platform.runner;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalyzerOutputBudgetTest {
    @TempDir
    Path root;

    @Test
    void toleratesOnlyDisappearingChildren() throws Exception {
        var visitor = new AnalyzerOutputBudget.BudgetVisitor(root, 100);
        Path temp = root.resolve("facts.ndjson.tmp");
        assertThat(visitor.visitFileFailed(temp, new NoSuchFileException(temp.toString())))
                .isEqualTo(FileVisitResult.CONTINUE);
        assertThatThrownBy(() -> visitor.visitFileFailed(root, new NoSuchFileException(root.toString())))
                .isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> visitor.visitFileFailed(temp, new AccessDeniedException(temp.toString())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void enforcesBytesAndCountsDirectories() throws Exception {
        Files.writeString(root.resolve("facts.ndjson"), "12345");
        AnalyzerOutputBudget.check(root, 5);
        assertThatThrownBy(() -> AnalyzerOutputBudget.check(root, 4)).isInstanceOf(IOException.class);
        for (int i = 0; i < 63; i++) Files.createDirectory(root.resolve("dir" + i));
        assertThatThrownBy(() -> AnalyzerOutputBudget.check(root, 100)).isInstanceOf(IOException.class);
    }

    @Test
    void boundsEvenDisappearingEntries() throws Exception {
        var visitor = new AnalyzerOutputBudget.BudgetVisitor(root, 100);
        for (int i = 0; i < 64; i++) visitor.visitFileFailed(root.resolve("tmp" + i), new NoSuchFileException("tmp"));
        assertThatThrownBy(() -> visitor.visitFileFailed(root.resolve("last"), new NoSuchFileException("last")))
                .isInstanceOf(IOException.class)
                .hasMessage("ANALYZER_OUTPUT_LIMIT");
    }
}
