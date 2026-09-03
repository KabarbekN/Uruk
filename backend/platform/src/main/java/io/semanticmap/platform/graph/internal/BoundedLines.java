package io.semanticmap.platform.graph.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Consumes oversized records without retaining them, so later valid records survive. */
public final class BoundedLines implements AutoCloseable {
    public record Line(String text, boolean oversized, boolean invalidUtf8) {}

    private final InputStream input;
    private final int limit;
    private final long byteBudget;
    private long bytes;

    public BoundedLines(InputStream input, int limit, long byteBudget) {
        this.input = input;
        this.limit = limit;
        this.byteBudget = byteBudget;
    }

    public Line next() throws IOException {
        var buffer = new ByteArrayOutputStream(Math.min(limit, 4096));
        boolean oversized = false;
        int value;
        while ((value = input.read()) != -1) {
            if (++bytes > byteBudget) throw new IOException("STREAM_BYTE_LIMIT");
            if (value == '\n') break;
            if (buffer.size() < limit) buffer.write(value);
            else oversized = true;
        }
        if (value == -1 && buffer.size() == 0 && !oversized) return null;
        byte[] data = buffer.toByteArray();
        int length = data.length;
        if (length > 0 && data[length - 1] == '\r') length--;
        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, 0, length))
                    .toString();
            return new Line(text, oversized, false);
        } catch (java.nio.charset.CharacterCodingException ex) {
            return new Line("[invalid UTF-8]", oversized, true);
        }
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
