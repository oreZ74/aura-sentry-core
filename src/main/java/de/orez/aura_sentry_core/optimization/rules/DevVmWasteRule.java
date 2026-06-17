package de.orez.aura_sentry_core.optimization.rules;

import java.util.Optional;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.model.AzureResource;
import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.ResourceCategory;
import de.orez.aura_sentry_core.optimization.OptimizationRule;

/**
 * Rule: IaaS resources tagged with "environment=dev" are flagged as
 * potential cost waste since dev workloads rarely need production-grade
 * SKU tiers.
 *
 * <p><b>Guard clause:</b> This rule only applies to
 * {@link ResourceCategory.VirtualMachine} because PaaS resources
 * (App Insights, Logic Apps, Smart Detectors, etc.) do not expose
 * traditional CPU/RAM utilisation metrics and must never be flagged
 * as {@code OVERPROVISIONED} by this rule.
 */
public class DevVmWasteRule implements OptimizationRule {

    private static final String DEV_ENVIRONMENT_TAG_KEY = "environment";
    private static final String DEV_ENVIRONMENT_TAG_VALUE = "dev";

    private static final OptimizationFinding OVERPROVISIONED_FINDING = new OptimizationFinding(
            IssueType.OVERPROVISIONED,
            "Dev-VM detected with environment=dev tag — "
                    + "consider downsizing to a lower-cost SKU tier.");

    @Override
    public Optional<OptimizationFinding> evaluate(ResourceCategory category) {
        return switch (category) {
            // IaaS only – VMs have measurable CPU/RAM, PaaS does not
            case ResourceCategory.VirtualMachine(var r) when isDevEnvironment(r) ->
                    Optional.of(OVERPROVISIONED_FINDING);
            // All other categories (Storage, Network, Other/PaaS) — no utilisation check
            default -> Optional.empty();
        };
    }

    private boolean isDevEnvironment(AzureResource resource) {
        if (resource.tags() == null) {
            return false;
        }
        // Case-insensitive key lookup: "Environment", "environment", "ENVIRONMENT" all match
        return resource.tags().entrySet().stream()
                .anyMatch(e -> DEV_ENVIRONMENT_TAG_KEY.equalsIgnoreCase(e.getKey())
                        && DEV_ENVIRONMENT_TAG_VALUE.equalsIgnoreCase(e.getValue()));
    }
}
