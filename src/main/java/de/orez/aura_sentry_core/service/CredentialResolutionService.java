package de.orez.aura_sentry_core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.orez.aura_sentry_core.config.SecurityContextProvider;

import de.orez.aura_sentry_core.persistence.entity.CloudCredentialsEntity;
import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import de.orez.aura_sentry_core.persistence.repository.CloudCredentialsRepository;

/**
 * Single source of truth for loading, decrypting, and validating cloud
 * credentials for the currently authenticated user.
 *
 * <p>Eliminates the 5-way code duplication that previously existed across
 * {@code AzureResourceService}, {@code AzureCostService},
 * {@code RemediationAgent}, {@code ConfigController}, and
 * {@code SettingsController}.
 */
@Service
public class CredentialResolutionService {

    private static final Logger log = LoggerFactory.getLogger(CredentialResolutionService.class);

    private static final String PLACEHOLDER_UUID = "00000000-0000-0000-0000-000000000000";
    private static final String PLACEHOLDER_TEXT = "configure-via-dashboard";

    private final CloudCredentialsRepository credentialsRepository;
    private final CredentialEncryptionService encryptionService;
    private final SecurityContextProvider securityContextProvider;

    public CredentialResolutionService(CloudCredentialsRepository credentialsRepository,
                                       CredentialEncryptionService encryptionService,
                                       SecurityContextProvider securityContextProvider) {
        this.credentialsRepository = credentialsRepository;
        this.encryptionService = encryptionService;
        this.securityContextProvider = securityContextProvider;
    }

    /**
     * Fully decrypted credentials for the current user.
     */
    public record ResolvedCredentials(
            String tenantId, String clientId, String clientSecret,
            String subscriptionId, String geminiApiKey) {}

    /**
     * Resolves the currently authenticated user from the Spring Security context.
     *
     * @throws IllegalStateException if no user is authenticated or the user is
     *                               not found in the database
     */
    public UserEntity resolveCurrentUser() {
        return securityContextProvider.getCurrentUser();
    }

    /**
     * Loads and decrypts all credentials for the current user without
     * placeholder validation.
     */
    public ResolvedCredentials resolveCredentials() {
        return resolveCredentials(resolveCurrentUser());
    }

    /**
     * Loads and decrypts all credentials for a specific user without
     * placeholder validation.
     */
    public ResolvedCredentials resolveCredentials(UserEntity currentUser) {
        CloudCredentialsEntity creds = credentialsRepository
                .findTopByUserOrderByUpdatedAtDesc(currentUser)
                .orElseThrow(() -> new IllegalStateException(
                        "No Azure credentials configured for '" + currentUser.getUsername()
                                + "'. Use Settings to save your credentials."));

        return new ResolvedCredentials(
                decryptField("tenant_id", creds.getEncryptedTenantId()),
                decryptField("client_id", creds.getEncryptedClientId()),
                decryptField("client_secret", creds.getEncryptedClientSecret()),
                decryptField("subscription_id", creds.getEncryptedSubscriptionId()),
                creds.getEncryptedGeminiApiKey() != null
                        ? decryptField("gemini_api_key", creds.getEncryptedGeminiApiKey())
                        : "");
    }

    /**
     * Loads, decrypts, and validates all credentials.
     * Throws if any field still contains a placeholder value,
     * preventing the Azure SDK from blocking with retry loops.
     */
    public ResolvedCredentials resolveAndValidateCredentials() {
        ResolvedCredentials creds = resolveCredentials();
        if (isPlaceholder(creds.tenantId()) || isPlaceholder(creds.clientId())
                || isPlaceholder(creds.clientSecret()) || isPlaceholder(creds.subscriptionId())) {
            throw new IllegalStateException(
                    "Azure credentials are not configured yet. "
                    + "Open the dashboard Settings and enter your real Azure service-principal credentials "
                    + "(tenant ID, client ID, client secret, subscription ID).");
        }
        return creds;
    }

    /**
     * Checks whether a decrypted credential value is still a placeholder
     * that should not be used for an Azure API call.
     */
    public boolean isPlaceholder(String value) {
        if (value == null) {
            return true;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty()
                || trimmed.equalsIgnoreCase(PLACEHOLDER_UUID)
                || trimmed.equalsIgnoreCase(PLACEHOLDER_TEXT);
    }

    private String decryptField(String fieldName, String ciphertext) {
        try {
            return encryptionService.decrypt(ciphertext);
        } catch (Exception e) {
            log.error("[CredentialResolution] Failed to decrypt '{}'. "
                    + "The stored value is not valid AES-256-GCM ciphertext. "
                    + "Use POST /api/v1/config (Settings UI) to save credentials properly.", fieldName);
            throw new IllegalStateException(
                    "Credential decryption failed for field '" + fieldName + "'. "
                    + "Re-save credentials through the Settings UI.", e);
        }
    }
}
