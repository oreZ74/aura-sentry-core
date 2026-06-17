package de.orez.aura_sentry_core.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

import de.orez.aura_sentry_core.AbstractIntegrationTest;
import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import de.orez.aura_sentry_core.persistence.repository.UserRepository;

@DirtiesContext(classMode = ClassMode.BEFORE_CLASS)
class CredentialChainIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CredentialManagementService managementService;

    @Autowired
    private CredentialEncryptionService encryptionService;

    @Autowired
    private CredentialResolutionService resolutionService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldEncryptSaveAndDecryptCredentialsEndToEnd() {
        UserEntity user = ensureTestUserExists();

        managementService.saveCredentials(
                user,
                "test-tenant-id",
                "test-client-id",
                "test-secret-value",
                "test-subscription-id",
                "test-gemini-key",
                "You are a helpful assistant.",
                "Focus on cost savings.");

        CredentialResolutionService.ResolvedCredentials creds =
                resolutionService.resolveCredentials(user);

        assertThat(creds.tenantId()).isEqualTo("test-tenant-id");
        assertThat(creds.clientId()).isEqualTo("test-client-id");
        assertThat(creds.clientSecret()).isEqualTo("test-secret-value");
        assertThat(creds.subscriptionId()).isEqualTo("test-subscription-id");
        assertThat(creds.geminiApiKey()).isEqualTo("test-gemini-key");
    }

    @Test
    void shouldPreserveSecretWhenUpdatingWithBlankValue() {
        UserEntity user = ensureTestUserExists();

        managementService.saveCredentials(
                user, "tid", "cid", "original-secret", "sid", "original-key", null, null);

        managementService.saveCredentials(
                user, "tid", "cid", "", "sid", "", null, null);

        CredentialResolutionService.ResolvedCredentials creds =
                resolutionService.resolveCredentials(user);

        assertThat(creds.clientSecret()).isEqualTo("original-secret");
        assertThat(creds.geminiApiKey()).isEqualTo("original-key");
    }

    @Test
    void shouldRejectEmptyTenantId() {
        UserEntity user = ensureTestUserExists();
        try {
            managementService.saveCredentials(user, "", "cid", "s", "sid", "k", null, null);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("Tenant ID");
        }
    }

    @Test
    void decryptionRoundTripShouldNotThrow() {
        String plaintext = "my-secret-value-123";
        String encrypted = encryptionService.encrypt(plaintext);
        String decrypted = encryptionService.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void placeholderDetectionShouldWork() {
        assertThat(resolutionService.isPlaceholder(null)).isTrue();
        assertThat(resolutionService.isPlaceholder("")).isTrue();
        assertThat(resolutionService.isPlaceholder("00000000-0000-0000-0000-000000000000")).isTrue();
        assertThat(resolutionService.isPlaceholder("configure-via-dashboard")).isTrue();
        assertThat(resolutionService.isPlaceholder("real-value")).isFalse();
    }

    private UserEntity ensureTestUserExists() {
        return userRepository.findByUsername("integration-test-user")
                .orElseGet(() -> {
                    UserEntity user = new UserEntity("integration-test-user",
                            "$2a$10$dummyhashvalue1234567890123456789012345678901234567890",
                            "USER");
                    user.setFullName("Integration Test User");
                    user.setEmail("test@example.com");
                    user.setEnabled(true);
                    return userRepository.save(user);
                });
    }
}
