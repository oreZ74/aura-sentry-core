package de.orez.aura_sentry_core.controller.dto.config;

import jakarta.validation.constraints.NotBlank;

/**
 * Inbound DTO for saving Azure + Gemini credentials via the config REST API.
 *
 * <p>{@code clientSecret} and {@code geminiApiKey} are <b>optional on updates</b>:
 * an empty or masked string means &quot;preserve the existing encrypted value&quot;.
 * On initial setup both are required (validated in the controller).
 */
public record CloudCredentialsRequest(
        @NotBlank String tenantId,
        @NotBlank String clientId,
        String clientSecret,
        @NotBlank String subscriptionId,
        String geminiApiKey,
        String systemTemplate,
        String specificInstructions) {
}
