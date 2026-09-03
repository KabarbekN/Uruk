package io.semanticmap.analyzer.java;

import io.semanticmap.contract.Protocol;
import java.util.*;
import org.openrewrite.PrintOutputCapture;
import org.openrewrite.java.JavaPrinter;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;

final class SourceUnit {
    final String path;
    final String source;
    final J.CompilationUnit tree;
    final Map<UUID, int[]> positions = new HashMap<>();
    private final List<Integer> lineStarts = new ArrayList<>();
    private final List<String> lines;

    SourceUnit(String path, String source, J.CompilationUnit tree) {
        this.path = path;
        this.source = source;
        this.tree = tree;
        lineStarts.add(0);
        for (int i = 0; i < source.length(); i++) if (source.charAt(i) == '\n') lineStarts.add(i + 1);
        lines = Arrays.asList(source.split("\\r?\\n", -1));
        // The lossless printer measures actual source offsets, including comments and CRLF.
        PrintOutputCapture<Void> output = new PrintOutputCapture<>(null);
        new JavaPrinter<Void>() {
            @Override
            protected void beforeSyntax(J j, Space.Location loc, PrintOutputCapture<Void> p) {
                super.beforeSyntax(j, loc, p);
                positions.computeIfAbsent(j.getId(), ignored -> new int[2])[0] =
                        p.getOut().length();
            }

            @Override
            protected void afterSyntax(J j, PrintOutputCapture<Void> p) {
                super.afterSyntax(j, p);
                positions.computeIfAbsent(j.getId(), ignored -> new int[2])[1] =
                        p.getOut().length();
            }
        }.visit(tree, output);
        if (!source.equals(output.getOut()))
            throw new IllegalArgumentException("Lossless source round-trip failed: " + path);
    }

    Protocol.Evidence evidence(J node) {
        int[] range = positions.get(node.getId());
        if (range == null || range[1] < range[0])
            throw new IllegalArgumentException(
                    "Missing source range for " + node.getClass().getSimpleName());
        int start = Math.min(range[0], source.length());
        int last = Math.max(start, range[1] - 1);
        int firstLine = line(start);
        int lastLine = line(last);
        return new Protocol.Evidence(
                path,
                firstLine + 1,
                start - lineStarts.get(firstLine) + 1,
                lastLine + 1,
                last - lineStarts.get(lastLine) + 2,
                Protocol.hash(String.join("\n", lines.subList(firstLine, lastLine + 1))));
    }

    private int line(int offset) {
        int found = Collections.binarySearch(lineStarts, offset);
        return found >= 0 ? found : -found - 2;
    }

    boolean test() {
        return path.contains("/src/test/") || path.startsWith("src/test/") || path.contains("/test/");
    }
}
