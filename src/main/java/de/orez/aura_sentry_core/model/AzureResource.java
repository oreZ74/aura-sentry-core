package de.orez.aura_sentry_core.model;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable representation of an Azure resource with comprehensive metadata.
 *
 * Modern Java 25 Record with all required fields for:
 * - Cost analysis (sku, region)
 * - Resource governance (tags, state)
 * - AI-driven remediation (region context, SKU tier)
 */
public record AzureResource(
        String id,
        String name,
        String type,
        String resourceGroup,
        Map<String, String> tags,
        ResourceState state,
        Instant createdAt,
        String sku,
        String region,
        Map<String, Double> metrics) {
}
