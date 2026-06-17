package de.orez.aura_sentry_core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;

/**
 * Factory for on-the-fly AzureResourceManager instances.
 *
 * Replaces the previous singleton {@code AzureConfig} approach:
 * credentials are now read from the encrypted database store and passed
 * to this factory at scan time, enabling dynamic multi-tenant support
 * and removing hardcoded environment-variable dependencies.
 */
@Component
public class AzureResourceManagerFactory {

    private static final Logger log = LoggerFactory.getLogger(AzureResourceManagerFactory.class);

    /**
     * Creates a fresh {@link AzureResourceManager} authenticated with the given
     * service-principal credentials.
     *
     * @param tenantId       Azure AD tenant ID
     * @param clientId       service-principal application (client) ID
     * @param clientSecret   service-principal client secret
     * @param subscriptionId target Azure subscription ID
     * @return a fully authenticated ARM client
     */
    public AzureResourceManager create(String tenantId, String clientId,
                                       String clientSecret, String subscriptionId) {
        log.info("Creating on-the-fly AzureResourceManager for subscription {}", subscriptionId);

        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .tenantId(tenantId)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();

        var profile = new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE);

        return AzureResourceManager.authenticate(credential, profile)
                .withSubscription(subscriptionId);
    }
}
