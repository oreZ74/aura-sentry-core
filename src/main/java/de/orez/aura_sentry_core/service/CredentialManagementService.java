package de.orez.aura_sentry_core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.orez.aura_sentry_core.persistence.entity.AiConfigEntity;
import de.orez.aura_sentry_core.persistence.entity.CloudCredentialsEntity;
import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import de.orez.aura_sentry_core.persistence.repository.AiConfigRepository;
import de.orez.aura_sentry_core.persistence.repository.CloudCredentialsRepository;

/**
 * Centralised credential save/update logic extracted from
 * {@code ConfigController} and {@code SettingsController}.
 *
 * <p>Implements the Delete-Flush-Save cycle with secret-preservation:
 * blank or masked values on update retain the previously encrypted secret.
 */
@Service
public class CredentialManagementService {

    private static final Logger log = LoggerFactory.getLogger(CredentialManagementService.class);

    public static final String SECRET_MASK = "••••••••••••••••";

    private final CloudCredentialsRepository credentialsRepository;
    private final CredentialEncryptionService encryptionService;
    private final AiConfigRepository aiConfigRepository;

    public CredentialManagementService(CloudCredentialsRepository credentialsRepository,
                                       CredentialEncryptionService encryptionService,
                                       AiConfigRepository aiConfigRepository) {
        this.credentialsRepository = credentialsRepository;
        this.encryptionService = encryptionService;
        this.aiConfigRepository = aiConfigRepository;
    }

    /**
     * Saves (or updates) encrypted Azure and Gemini credentials for a user
     * with secret-preservation semantics.
     *
     * @param currentUser          the authenticated user
     * @param tenantId             Azure AD tenant ID (plaintext, will be encrypted)
     * @param clientId             service-principal application ID
     * @param clientSecret         client secret or blank/masked to preserve the old value
     * @param subscriptionId       Azure subscription ID
     * @param geminiApiKey         Gemini API key or blank/masked to preserve the old value
     * @param systemTemplate       optional custom AI system prompt (may be {@code null})
     * @param specificInstructions optional extra AI instructions (may be {@code null})
     * @return the newly persisted {@link CloudCredentialsEntity}
     * @throws IllegalArgumentException if required secrets are missing on initial setup
     */
    @Transactional
    public CloudCredentialsEntity saveCredentials(UserEntity currentUser,
                                                  String tenantId, String clientId,
                                                  String clientSecret, String subscriptionId,
                                                  String geminiApiKey,
                                                  String systemTemplate, String specificInstructions) {

        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID is required.");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("Client ID is required.");
        }
        if (subscriptionId == null || subscriptionId.isBlank()) {
            throw new IllegalArgumentException("Subscription ID is required.");
        }

        var existing = credentialsRepository.findTopByUserOrderByUpdatedAtDesc(currentUser);
        String oldSecret = existing.map(CloudCredentialsEntity::getEncryptedClientSecret).orElse(null);
        String oldGemini = existing.map(CloudCredentialsEntity::getEncryptedGeminiApiKey).orElse(null);

        existing.ifPresent(e -> {
            credentialsRepository.delete(e);
            credentialsRepository.flush();
            log.info("[CredentialMgmt] Deleted previous credential id={}", e.getId());
        });

        boolean isNewSetup = (oldSecret == null);

        if (isNewSetup && isBlankOrPlaceholder(clientSecret)) {
            throw new IllegalArgumentException("Client Secret is required for initial setup.");
        }
        if (isNewSetup && isBlankOrPlaceholder(geminiApiKey)) {
            throw new IllegalArgumentException("Gemini API Key is required for initial setup.");
        }

        CloudCredentialsEntity entity = new CloudCredentialsEntity();
        entity.setUser(currentUser);
        entity.setEncryptedTenantId(encryptionService.encrypt(tenantId));
        entity.setEncryptedClientId(encryptionService.encrypt(clientId));
        entity.setEncryptedSubscriptionId(encryptionService.encrypt(subscriptionId));

        entity.setEncryptedClientSecret(isBlankOrPlaceholder(clientSecret) && oldSecret != null
                ? oldSecret
                : encryptionService.encrypt(isBlankOrPlaceholder(clientSecret)
                        ? "configure-via-dashboard" : clientSecret));

        entity.setEncryptedGeminiApiKey(isBlankOrPlaceholder(geminiApiKey) && oldGemini != null
                ? oldGemini
                : encryptionService.encrypt(isBlankOrPlaceholder(geminiApiKey)
                        ? "configure-via-dashboard" : geminiApiKey));

        CloudCredentialsEntity saved = credentialsRepository.saveAndFlush(entity);
        log.info("[CredentialMgmt] Saved credential id={} for user '{}'", saved.getId(), currentUser.getUsername());

        if (systemTemplate != null || specificInstructions != null) {
            AiConfigEntity aiConfig = aiConfigRepository.findByUserUsername(currentUser.getUsername())
                    .orElseGet(() -> {
                        AiConfigEntity cfg = new AiConfigEntity();
                        cfg.setUser(currentUser);
                        return cfg;
                    });
            if (systemTemplate != null) {
                aiConfig.setSystemTemplate(systemTemplate);
            }
            if (specificInstructions != null) {
                aiConfig.setSpecificInstructions(specificInstructions);
            }
            aiConfigRepository.save(aiConfig);
            log.info("[CredentialMgmt] AI config saved for '{}'", currentUser.getUsername());
        }

        return saved;
    }

    /**
     * Returns {@code true} if the value is {@code null}, blank, or a known
     * placeholder/mask that should <b>not</b> overwrite an existing secret.
     */
    public static boolean isBlankOrPlaceholder(String value) {
        return value == null
                || value.isBlank()
                || SECRET_MASK.equals(value)
                || "configure-via-dashboard".equalsIgnoreCase(value)
                || "00000000-0000-0000-0000-000000000000".equals(value);
    }
}
