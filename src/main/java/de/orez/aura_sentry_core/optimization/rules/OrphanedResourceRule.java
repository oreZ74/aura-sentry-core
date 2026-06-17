package de.orez.aura_sentry_core.optimization.rules;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.model.AzureResource;
import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.ResourceCategory;
import de.orez.aura_sentry_core.optimization.OptimizationRule;

/**
 * Rule: any resource without tags is classified as ORPHANED because
 * ownership and purpose cannot be determined.
 * <p>
 * Exception: resources created less than 7 days ago are flagged as
 * {@code NEW_RESOURCE} instead of {@code ORPHANED}. A freshly provisioned
 * resource (e.g. Application Insights with tag Environment=Dev) must not be
 * immediately classified as orphaned just because it has no utilization
 * history yet. Only from day 8 does the zero-utilization check apply.
 * <p>
 * Uses Java 25 pattern matching for switch across all ResourceCategory types.
 */
public class OrphanedResourceRule implements OptimizationRule {

    private static final long NEW_RESOURCE_GRACE_DAYS = 7;

    private static final OptimizationFinding ORPHANED_FINDING = new OptimizationFinding(
            IssueType.ORPHANED,
            "Resource has no tags -- ownership and purpose cannot be determined.");

    private static final OptimizationFinding NEW_RESOURCE_FINDING = new OptimizationFinding(
            IssueType.NEW_RESOURCE,
            "Resource is less than 7 days old -- exempt from orphaned classification.");

    @Override
    public Optional<OptimizationFinding> evaluate(ResourceCategory category) {
        return switch (category) {
            case ResourceCategory.VirtualMachine(var r) when hasNoTags(r) -> classify(r);
            case ResourceCategory.StorageAccount(var r) when hasNoTags(r) -> classify(r);
            case ResourceCategory.NetworkInterface(var r) when hasNoTags(r) -> classify(r);
            case ResourceCategory.Other(var r) when hasNoTags(r) -> classify(r);
            default -> Optional.empty();
        };
    }

    private Optional<OptimizationFinding> classify(AzureResource resource) {
        if (isNewResource(resource)) {
            return Optional.of(NEW_RESOURCE_FINDING);
        }
        return Optional.of(ORPHANED_FINDING);
    }

    private boolean isNewResource(AzureResource resource) {
        Instant created = resource.createdAt();
        if (created == null) {
            return false;
        }
        long daysSinceCreation = ChronoUnit.DAYS.between(created, Instant.now());
        return daysSinceCreation < NEW_RESOURCE_GRACE_DAYS;
    }

    private boolean hasNoTags(AzureResource resource) {
        return resource.tags() == null || resource.tags().isEmpty();
    }
}
