package de.orez.aura_sentry_core.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM encryption service for protecting database-stored credentials.
 *
 * Encrypt/Decrypt semantics:
 * <ul>
 *   <li>A random 12-byte IV is generated per encrypt call</li>
 *   <li>IV is prepended to the ciphertext before Base64 encoding</li>
 *   <li>GCM authentication tag (128 bit) provides integrity verification</li>
 * </ul>
 *
 * Requires a 32-byte Base64-encoded key in {@code app.encryption.key}.
 * Generate one with: {@code openssl rand -base64 32}
 */
@Service
public class CredentialEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptionService.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKey secretKey;

    public CredentialEncryptionService(@Value("${app.encryption.key}") String base64Key) {
        byte[] decoded = Base64.getDecoder().decode(base64Key);
        if (decoded.length != 32) {
            throw new IllegalArgumentException(
                    "app.encryption.key must be a 32-byte Base64-encoded string. "
                    + "Generate with: openssl rand -base64 32");
        }
        this.secretKey = new SecretKeySpec(decoded, "AES");
        log.info("CredentialEncryptionService initialized with AES-256-GCM");
    }

    /**
     * Encrypts a plaintext string.
     *
     * @param plaintext the secret to encrypt
     * @return Base64-encoded IV + ciphertext
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Cannot encrypt null or blank plaintext");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new IllegalStateException("Credential encryption failed", e);
        }
    }

    /**
     * Decrypts a Base64-encoded ciphertext string.
     *
     * @param ciphertext Base64-encoded IV + ciphertext
     * @return the original plaintext
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("Cannot decrypt null or blank ciphertext");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(ciphertext);
            ByteBuffer buffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed – key rotation or data corruption suspected", e);
            throw new IllegalStateException("Credential decryption failed", e);
        }
    }
}
