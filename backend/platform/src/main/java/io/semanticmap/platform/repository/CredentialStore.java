package io.semanticmap.platform.repository;

import io.semanticmap.platform.shared.Db;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CredentialStore {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;
    private static final int IV_LENGTH_BYTE = 12;

    private final Db db;
    private final SecretKey secretKey;
    private final SecureRandom random = new SecureRandom();

    public CredentialStore(
            Db db, @Value("${semantic.security.credentials-key:0123456789abcdef0123456789abcdef}") String keyHex) {
        this.db = db;
        byte[] keyBytes = parseKey(keyHex);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String storeToken(UUID orgId, String token, String description) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token cannot be blank");
        }
        String refKey = "cred-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            byte[] iv = new byte[IV_LENGTH_BYTE];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] cipherText = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));

            String encodedPayload = Base64.getEncoder().encodeToString(cipherText);
            String encodedIv = Base64.getEncoder().encodeToString(iv);

            db.update(
                    """
                    INSERT INTO repository_credential (id, organization_id, reference_key, provider_type, encrypted_token, iv, description)
                    VALUES (?, ?, ?, 'TOKEN', ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    orgId,
                    refKey,
                    encodedPayload,
                    encodedIv,
                    description);

            return refKey;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt token", e);
        }
    }

    public String resolveToken(UUID orgId, String referenceKey) {
        if (referenceKey == null || referenceKey.isBlank()) {
            return null;
        }
        var rows = db.rows(
                "SELECT encrypted_token, iv FROM repository_credential WHERE organization_id = ? AND reference_key = ?",
                orgId,
                referenceKey);
        if (rows.isEmpty()) {
            return null;
        }
        return decrypt((String) rows.getFirst().get("encryptedToken"), (String)
                rows.getFirst().get("iv"));
    }

    public String resolveToken(String referenceKey) {
        if (referenceKey == null || referenceKey.isBlank()) {
            return null;
        }
        var rows =
                db.rows("SELECT encrypted_token, iv FROM repository_credential WHERE reference_key = ?", referenceKey);
        if (rows.isEmpty()) {
            return null;
        }
        return decrypt((String) rows.getFirst().get("encryptedToken"), (String)
                rows.getFirst().get("iv"));
    }

    private String decrypt(String encryptedPayload, String encodedIv) {
        try {
            byte[] cipherText = Base64.getDecoder().decode(encryptedPayload);
            byte[] iv = Base64.getDecoder().decode(encodedIv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] plainText = cipher.doFinal(cipherText);

            return new String(plainText, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt token", e);
        }
    }

    private static byte[] parseKey(String hex) {
        if (hex == null || hex.length() != 32) {
            // Default 256-bit fallback key if not exactly 32 chars
            byte[] fallback = new byte[32];
            byte[] src = "semantic-map-secure-key-padding!".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(src, 0, fallback, 0, Math.min(src.length, 32));
            return fallback;
        }
        return hex.getBytes(StandardCharsets.UTF_8);
    }
}
