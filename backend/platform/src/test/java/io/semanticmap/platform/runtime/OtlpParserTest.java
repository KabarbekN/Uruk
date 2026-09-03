package io.semanticmap.platform.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OtlpParserTest {
    private final OtlpParser parser = new OtlpParser();
    private final ObjectMapper json = new ObjectMapper();
    private static final String SPAN =
            """
        {"traceId":"ABCDEF0123456789ABCDEF0123456789","spanId":"1234567890abcdef",
         "name":"SELECT orders","kind":"SPAN_KIND_CLIENT","startTimeUnixNano":"1725000000000000000",
         "endTimeUnixNano":"1725000000000000500","attributes":[
          {"key":"semantic.stable_key","value":{"stringValue":"java:method:OrderService#createOrder"}},
          {"key":"db.system.name","value":{"stringValue":"postgresql"}},
          {"key":"db.operation.name","value":{"stringValue":"SELECT"}},
          {"key":"row.count","value":{"intValue":"42"}}],
         "status":{"code":"STATUS_CODE_OK"},
         "events":[{"name":"database.response","timeUnixNano":"1725000000000000400"}],
         "links":[{"traceId":"abcdef0123456789abcdef0123456789","spanId":"2222222222222222"}]}
        """;

    private List<OtlpParser.Span> parse(String span) {
        return parser.parse(new ByteArrayInputStream(
                ("{\"resourceSpans\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"orders\"}}]},\"scopeSpans\":[{\"scope\":{\"name\":\"instrumentation\"},\"spans\":["
                                + span + "]}]}]}")
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesGenuineOtlpTypedAttributesAndNanoseconds() {
        var span = parse(SPAN).getFirst();
        assertThat(span.traceId()).isEqualTo("abcdef0123456789abcdef0123456789");
        assertThat(span.startNanos()).isEqualTo(new BigInteger("1725000000000000000"));
        assertThat(span.attributes())
                .containsEntry("row.count", BigInteger.valueOf(42))
                .containsEntry("db.system.name", "postgresql");
        assertThat(span.resourceAttributes()).containsEntry("service.name", "orders");
        assertThat(span.events()).hasSize(1);
        assertThat(span.links()).hasSize(1);
    }

    @Test
    void rejectsMalformedIdsChronologyAndAttributeTypes() {
        for (String bad : List.of(
                SPAN.replace("ABCDEF0123456789ABCDEF0123456789", "00000000000000000000000000000000"),
                SPAN.replace("1234567890abcdef", "not-a-span"),
                SPAN.replace("1725000000000000500", "1724000000000000000"),
                SPAN.replace("1725000000000000000", "18446744073709551616"),
                SPAN.replace("\"intValue\":\"42\"", "\"intValue\":\"4.2\""),
                SPAN.replace("\"intValue\":\"42\"", "\"intValue\":42,\"stringValue\":\"42\""))) {
            assertThatThrownBy(() -> parse(bad)).isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void rejectsDuplicateKeysAndTrailingJson() {
        assertThatThrownBy(() -> parse(SPAN.replace("\"name\":\"SELECT orders\"", "\"name\":\"one\",\"name\":\"two\"")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> parser.parse(
                        new ByteArrayInputStream("{\"resourceSpans\":[]} {}".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void boundsBytesSpansAndNesting() throws Exception {
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(new byte[OtlpParser.MAX_BODY_BYTES + 1])))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> parse(String.join(",", java.util.Collections.nCopies(501, SPAN))))
                .isInstanceOf(ResponseStatusException.class);
        Object nested = Map.of("stringValue", "value");
        for (int i = 0; i < 12; i++) nested = Map.of("arrayValue", Map.of("values", List.of(nested)));
        String invalid = SPAN.replace("{\"intValue\":\"42\"}", json.writeValueAsString(nested));
        assertThatThrownBy(() -> parse(invalid)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void acceptsUnsignedNanosecondsAndEmptyExportWithoutInventingSpans() {
        var max = parse(SPAN.replace("1725000000000000500", "18446744073709551615"))
                .getFirst();
        assertThat(max.endNanos()).isEqualTo(new BigInteger("18446744073709551615"));
        assertThat(parser.parse(new ByteArrayInputStream("{\"resourceSpans\":[]}".getBytes(StandardCharsets.UTF_8))))
                .isEmpty();
    }
}
