package de.orez.aura_sentry_core.controller.dto.config;

/**
 * Outbound DTO that reveals whether credentials are configured without
 * exposing the full secrets – only a masked suffix is shown.
 */
public record CloudCredentialsResponse(
        Long id,
        String tenantId,
        String clientId,
        String clientSecret,
        String subscriptionId,
        String geminiApiKey,
        boolean configured,
        String systemTemplate,
        String specificInstructions,
        String systemTemplatePlaceholder,
        String specificInstructionsPlaceholder) {

    /**
     * Factory for the "no credentials configured" state.
     * Placeholders are still included so the AI config modal shows
     * the default prompt template even when credentials are missing.
     */
    public static CloudCredentialsResponse unconfigured(
            String systemTemplatePlaceholder, String specificInstructionsPlaceholder) {
        return new CloudCredentialsResponse(
                null, null, null, null, null, null, false, null, null,
                systemTemplatePlaceholder, specificInstructionsPlaceholder);
    }
}
