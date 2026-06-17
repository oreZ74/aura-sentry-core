package de.orez.aura_sentry_core.model;

import java.util.List;
import java.util.Map;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;

/**
 * Analysis result for a single Azure resource with metadata for remediation.
 *
 * <p>Carries a {@link CostSource} component that transparently communicates
 * <em>where</em> the cost data came from (Actual Billing, PricingEngine
 * heuristic, or Unavailable).  The derived {@link #costProfile()} accessor
 * assembles a fully-typed {@link CostProfile} from the record's components.
 *
 * <p>Backward-compatible: all existing JSON fields are preserved;
 * {@code costSource} is the only new field.
 */
public record OptimizationResult(
        String resourceId,
        String resourceName,
        String resourceType,
        String resourceGroup,
        ResourceState state,
        List<OptimizationFinding> findings,
        double estimatedMonthlyCost,
        double estimatedMonthlySavings,
        String currencySymbol,
        CostSource costSource,
        String sku,
        String region,
        Map<String, String> tags) {

    /**
     * Reconstructs a typed {@link CostProfile} from the record's components.
     */
    public CostProfile costProfile() {
        return switch (costSource) {
            case ACTUAL_BILLING, AMORTIZED ->
                    new CostProfile.ActualCost(estimatedMonthlyCost, currencySymbol);
            case ESTIMATED ->
                    new CostProfile.EstimatedCost(estimatedMonthlyCost, "reconstructed from OptimizationResult",
                            new CostProfile.PricingContext(resourceType, sku, region));
            case UNAVAILABLE ->
                    new CostProfile.UnavailableCost("reconstructed from OptimizationResult");
        };
    }

    /**
     * Factory for resources with no detected issues.
     */
    public static OptimizationResult healthy(AzureResource resource, CostProfile costProfile) {
        return new OptimizationResult(
                resource.id(),
                resource.name(),
                resource.type(),
                resource.resourceGroup(),
                resource.state(),
                List.of(new OptimizationFinding(IssueType.HEALTHY, "No issues detected.")),
                costProfile.amount(),
                0.0,
                costProfile.currencySymbol(),
                costProfile.source(),
                resource.sku(),
                resource.region(),
                resource.tags());
    }
}
