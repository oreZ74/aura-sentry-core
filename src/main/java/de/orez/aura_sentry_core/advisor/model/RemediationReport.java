package de.orez.aura_sentry_core.advisor.model;

import java.time.Instant;

/**
 * Immutable report produced by the AI Advisor for a single resource.
 * The {@code markdownReport} field contains the fully formatted Markdown text
 * including concrete CLI scripts, ready to be rendered directly in a frontend.
 */
public record RemediationReport(
        String resourceId,
        String resourceName,
        Instant generatedAt,
        String markdownReport) {
}
