package de.orez.aura_sentry_core.persistence.mapper;

import java.time.Instant;
import java.util.List;

import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.OptimizationResult;
import de.orez.aura_sentry_core.persistence.entity.ScanFindingEntity;
import de.orez.aura_sentry_core.persistence.entity.ScanResultEntity;

/**
 * Stateless mapper between immutable domain records and JPA entities.
 *
 * Why not MapStruct? For a mapping of this size the annotation-processor
 * dependency is not justified. This mapper is a plain utility type with no
 * side effects – easy to test and free of framework coupling.
 */
public final class ScanResultMapper {

    private ScanResultMapper() {
    }

    public static ScanResultEntity toEntity(OptimizationResult result, Instant scannedAt) {
        var entity = new ScanResultEntity(
                scannedAt,
                result.resourceId(),
                result.resourceName(),
                result.resourceType(),
                result.resourceGroup(),
                result.state());

        result.findings().forEach(finding -> {
            var findingEntity = new ScanFindingEntity(finding.flag(), finding.reason());
            entity.addFinding(findingEntity);
        });

        return entity;
    }

    public static OptimizationResult toRecord(ScanResultEntity entity) {
        List<OptimizationFinding> findings = entity.getFindings().stream()
                .map(f -> new OptimizationFinding(f.getFlag(), f.getReason()))
                .toList();

        return new OptimizationResult(
                entity.getResourceId(),
                entity.getResourceName(),
                entity.getResourceType(),
                entity.getResourceGroup(),
                entity.getState(),
                findings,
                0.0,
                0.0,
                "$",
                de.orez.aura_sentry_core.model.CostSource.UNAVAILABLE,
                "N/A",
                "Unknown",
                java.util.Map.of());
    }
}
