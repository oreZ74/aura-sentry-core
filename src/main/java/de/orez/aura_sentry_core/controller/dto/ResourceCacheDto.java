package de.orez.aura_sentry_core.controller.dto;

import java.time.Instant;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.model.ResourceState;

/**
 * Outbound DTO for the resource cache table.
 *
 * <p>Deliberately flat – no nested objects – so the frontend table
 * can render it without extra mapping logic.
 */
public record ResourceCacheDto(
        Long id,
        String azureId,
        String name,
        String type,
        String resourceGroup,
        ResourceState state,
        IssueType flag,
        double cost,
        String currency,
        String sku,
        String region,
        Instant scannedAt,
        Instant updatedAt) {
}