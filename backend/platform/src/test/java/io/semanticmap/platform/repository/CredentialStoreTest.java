package io.semanticmap.platform.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.semanticmap.platform.shared.Db;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CredentialStoreTest {
    private Db db;
    private CredentialStore store;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        db = Mockito.mock(Db.class);
        store = new CredentialStore(db, "0123456789abcdef0123456789abcdef");
    }

    @Test
    void storesAndResolvesToken() {
        String token = "ghp_secure_github_personal_access_token_12345";
        String ref = store.storeToken(orgId, token, "Test Token");

        assertThat(ref).startsWith("cred-");
        Mockito.verify(db)
                .update(
                        Mockito.contains("INSERT INTO repository_credential"),
                        Mockito.any(),
                        Mockito.eq(orgId),
                        Mockito.eq(ref),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.eq("Test Token"));
    }

    @Test
    void rejectsBlankToken() {
        assertThatThrownBy(() -> store.storeToken(orgId, "", "Empty")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.storeToken(orgId, null, "Null")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptsStoredToken() {
        String originalToken = "glpat-gitlab_access_token_99999";
        // Encrypt using real store
        String ref = store.storeToken(orgId, originalToken, "GitLab");

        // Mock DB returning what would be stored
        // To verify roundtrip, let's create a real instance with memory or captor
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        Mockito.verify(db)
                .update(
                        Mockito.anyString(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        captor.capture(),
                        Mockito.any(),
                        Mockito.any());
        String encryptedPayload = (String) captor.getValue();

        var ivCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        Mockito.verify(db)
                .update(
                        Mockito.anyString(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        ivCaptor.capture(),
                        Mockito.any());
        String iv = (String) ivCaptor.getValue();

        Mockito.when(db.rows(
                        Mockito.contains("WHERE organization_id = ? AND reference_key = ?"),
                        Mockito.eq(orgId),
                        Mockito.eq(ref)))
                .thenReturn(List.of(Map.of("encryptedToken", encryptedPayload, "iv", iv)));

        String decrypted = store.resolveToken(orgId, ref);
        assertThat(decrypted).isEqualTo(originalToken);
    }
}
