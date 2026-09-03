package io.semanticmap.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SecretRedactorTest {
    private final SecretRedactor redactor = new SecretRedactor();

    @Test
    void redactsCredentialsInsideSourceSnippets() {
        String source =
                """
            password="sensitivePassword"; Authorization: Bearer sensitiveBearerValue
            {"api_key":"sensitiveApiValue"}
            jdbc:postgresql://alice:sensitiveDbPassword@database.example/db
            -----BEGIN PRIVATE KEY-----
            sensitivePrivateKey
            -----END PRIVATE KEY-----
            sk-sensitiveProviderKey123
            order.total < 500
            """;
        assertThat(redactor.redact(source)).doesNotContain("sensitive").contains("order.total < 500", "[REDACTED]");
    }

    @Test
    void redactsNestedSecretFieldsAndUnterminatedKeys() throws Exception {
        var input = new ObjectMapper()
                .readTree(
                        "{\"constants\":{\"password\":\"hidden\",\"count\":500},\"items\":[{\"accessToken\":\"secretValue\"}]}");
        assertThat(redactor.redact(input).toString())
                .doesNotContain("hidden", "secretValue")
                .contains("500");
        assertThat(redactor.redact("-----BEGIN EC PRIVATE KEY-----\nunclosedSecret"))
                .isEqualTo("[REDACTED]");
    }
}
