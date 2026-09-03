package io.semanticmap.platform.runner;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Bounded, no-follow traversal that tolerates an analyzer's atomic file publication. */
public final class AnalyzerOutputBudget {
    private AnalyzerOutputBudget() {}

    public static void check(Path output, long limit) throws IOException {
        RunnerPolicy.rejectLinks(output);
        Files.walkFileTree(output, new BudgetVisitor(output, limit));
    }

    static final class BudgetVisitor extends SimpleFileVisitor<Path> {
        private final Path root;
        private final long limit;
        private long total;
        private int count;

        BudgetVisitor(Path root, long limit) {
            if (limit < 0) throw new IllegalArgumentException("Negative output limit");
            this.root = root;
            this.limit = limit;
        }

        private FileVisitResult inspect(BasicFileAttributes attrs) throws IOException {
            countEntry();
            if (attrs.isSymbolicLink()) throw new IOException("ANALYZER_OUTPUT_LIMIT");
            if (attrs.isOther()) throw new IOException("ANALYZER_SPECIAL_FILE_REJECTED");
            if (attrs.isRegularFile()) {
                if (attrs.size() > limit - total) throw new IOException("ANALYZER_OUTPUT_LIMIT");
                total += attrs.size();
            }
            return FileVisitResult.CONTINUE;
        }

        private void countEntry() throws IOException {
            if (++count > 64) throw new IOException("ANALYZER_OUTPUT_LIMIT");
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            return inspect(attrs);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            return inspect(attrs);
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
            countEntry();
            // Temporary output files can disappear between directory enumeration and stat.
            if (!file.equals(root) && failure instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
            throw failure;
        }
    }
}
