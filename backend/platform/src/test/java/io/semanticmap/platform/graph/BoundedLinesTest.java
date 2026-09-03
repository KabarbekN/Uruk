package io.semanticmap.platform.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.semanticmap.platform.graph.internal.BoundedLines;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BoundedLinesTest {
    @Test
    void discardsOversizedTailAndContinuesAtNextRecord() throws Exception {
        try (var lines = new BoundedLines(
                new ByteArrayInputStream(("x".repeat(100) + "\nvalid\r\nlast").getBytes(StandardCharsets.UTF_8)),
                10,
                1000)) {
            assertThat(lines.next().oversized()).isTrue();
            assertThat(lines.next().text()).isEqualTo("valid");
            assertThat(lines.next().text()).isEqualTo("last");
            assertThat(lines.next()).isNull();
        }
    }

    @Test
    void marksMalformedUtf8AndStillReadsNextRecord() throws Exception {
        try (var lines =
                new BoundedLines(new ByteArrayInputStream(new byte[] {(byte) 0xc3, 0x28, 10, 'o', 'k'}), 10, 100)) {
            assertThat(lines.next().invalidUtf8()).isTrue();
            assertThat(lines.next().text()).isEqualTo("ok");
        }
    }

    @Test
    void appliesStreamBudgetEvenToAnOversizedRecord() throws Exception {
        try (var lines = new BoundedLines(new ByteArrayInputStream(new byte[1000]), 10, 20)) {
            assertThatThrownBy(lines::next).isInstanceOf(IOException.class).hasMessage("STREAM_BYTE_LIMIT");
        }
    }
}
