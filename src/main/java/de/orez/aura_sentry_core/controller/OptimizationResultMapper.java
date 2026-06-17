package de.orez.aura_sentry_core.controller;

import java.util.List;

import org.springframework.stereotype.Component;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.model.AzureResource;
import de.orez.aura_sentry_core.model.CostSource;
import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.OptimizationResult;
import de.orez.aura_sentry_core.persistence.entity.ResourceCacheEntity;

@Component
public class OptimizationResultMapper {

    public OptimizationResult mapFromCache(ResourceCacheEntity entity) {
        return new OptimizationResult(
                entity.getAzureId(),
                entity.getName(),
                entity.getType(),
                entity.getResourceGroup(),
                entity.getState(),
                List.of(new OptimizationFinding(entity.getFlag(), "Cached from resource_cache")),
                entity.getCost() != null ? entity.getCost() : 0.0,
                0.0,
                entity.getCurrency() != null ? entity.getCurrency() : "USD",
                CostSource.ACTUAL_BILLING,
                entity.getSku(),
                entity.getRegion(),
                java.util.Map.of());
    }

    public OptimizationResult mapFromDemoResource(AzureResource r) {
        return new OptimizationResult(
                r.id(), r.name(), r.type(), r.resourceGroup(),
                r.state(),
                List.of(new OptimizationFinding(IssueType.HEALTHY, "Demo — no engine finding")),
                0.0, 0.0, "USD", CostSource.ESTIMATED,
                r.sku(), r.region(), java.util.Map.of());
    }
}
