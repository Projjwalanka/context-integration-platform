package com.bank.aiassistant.service.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * AES-256-GCM encryption/decryption for connector credentials at rest.
 *
 * <p>Enterprise considerations:
 * <ul>
 *   <li>Production: Replace with AWS KMS, Azure Key Vault, or HashiCorp Vault.</li>
 *   <li>The encryption key is loaded from {@code app.encryption.key} (32-byte Base64).</li>
 *   <li>A random 96-bit IV is prepended to every ciphertext.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorCredentialService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH  = 12;   // 96 bits
    private static final int GCM_TAG_LENGTH = 128;  // bits

    @Value("${app.encryption.key}")
    private String base64EncryptionKey;

    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────────────────────────────────
    // Encrypt
    // ─────────────────────────────────────────────────────────────────────────

    public String encrypt(Map<String, String> credentials) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64EncryptionKey);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] plaintext = objectMapper.writeValueAsBytes(credentials);
            byte[] ciphertext = cipher.doFinal(plaintext);

            // Prepend IV: [iv (12 bytes)][ciphertext]
            byte[] packed = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv).put(ciphertext).array();
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to encrypt credentials", ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Decrypt
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, String> decrypt(String encryptedCredentials) {
        try {
            byte[] packed = Base64.getDecoder().decode(encryptedCredentials);
            ByteBuffer buf = ByteBuffer.wrap(packed);

            byte[] iv = new byte[GCM_IV_LENGTH];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            byte[] keyBytes = Base64.getDecoder().decode(base64EncryptionKey);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            return objectMapper.readValue(plaintext, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new RuntimeException("Failed to decrypt credentials", ex);
        }
    }
}
