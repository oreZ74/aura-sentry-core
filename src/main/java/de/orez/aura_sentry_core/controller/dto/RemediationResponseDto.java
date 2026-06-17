package de.orez.aura_sentry_core.controller.dto;

/**
 * Response payload for remediation operations.
 */
public record RemediationResponseDto(
        String status,
        String message,
        String resourceId
) {
}
