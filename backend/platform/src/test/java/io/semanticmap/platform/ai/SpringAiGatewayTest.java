package io.semanticmap.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.semanticmap.platform.settings.AiConfiguration;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SpringAiGatewayTest {
    @Test
    void realSpringAiClientUsesStrictSchemaAndRecordsUsageAgainstLocalHttpStub() throws Exception {
        var json = new ObjectMapper();
        var request = new AtomicReference<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(json.readTree(exchange.getRequestBody()));
            byte[] body =
                    "{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":2,\"total_tokens\":14}}"
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var gateway = new SpringAiGateway(new AiConfiguration(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-model", "", "127.0.0.1", 100));
        try {
            UUID id = UUID.randomUUID();
            AtomicInteger transfers = new AtomicInteger();
            var reply = gateway.enrich(
                    new EvidenceBundle(id, id, id, id, "{\"facts\":[]}", "hash", Map.of()), transfers::incrementAndGet);
            assertThat(reply.content()).isEqualTo("{}");
            assertThat(reply.inputTokens()).isEqualTo(12);
            assertThat(reply.outputTokens()).isEqualTo(2);
            assertThat(transfers.get()).isEqualTo(1);
            assertThat(request.get().at("/response_format/type").asText()).isEqualTo("json_schema");
            assertThat(request.get().at("/response_format/json_schema/strict").asBoolean())
                    .isTrue();
            assertThat(request.get().at("/store").asBoolean(true)).isFalse();
            assertThat(request.get().at("/messages/0/content").asText()).contains("UNTRUSTED DATA");
            assertThat(request.get().hasNonNull("tools")).isFalse();
        } finally {
            gateway.close();
            server.stop(0);
        }
    }

    @Test
    void retriesTransientHttpFailuresOnceAndDoesNotRetryBadRequests() throws Exception {
        for (int status : new int[] {503, 400}) {
            AtomicInteger attempts = new AtomicInteger();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                attempts.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"error\":{\"message\":\"test failure\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            var gateway = new SpringAiGateway(new AiConfiguration(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test", "", "127.0.0.1", 100));
            try {
                UUID id = UUID.randomUUID();
                assertThatThrownBy(() ->
                                gateway.enrich(new EvidenceBundle(id, id, id, id, "{}", "hash", Map.of()), () -> {}))
                        .isInstanceOf(ResponseStatusException.class);
                assertThat(attempts.get()).isEqualTo(status == 503 ? 2 : 1);
            } finally {
                gateway.close();
                server.stop(0);
            }
        }
    }
}
