package de.orez.aura_sentry_core.optimization.rules;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.model.AzureResource;
import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.ResourceCategory;
import de.orez.aura_sentry_core.optimization.OptimizationRule;

/**
 * Rule: resources must carry a defined set of mandatory governance tags.
 *
 * <p>Checks for the presence (not the value) of:
 * <ul>
 *   <li>{@code Environment}</li>
 *   <li>{@code Owner}</li>
 *   <li>{@code CostCenter}</li>
 * </ul>
 *
 * <p>Resources with {@code tags == null}, an empty tag map, or missing
 * mandatory keys ALL trigger this rule. It runs in parallel with
 * {@link OrphanedResourceRule} – the {@link AzureScanService} severity
 * prioritisation ensures {@link IssueType#GOVERNANCE_MISSING} outranks
 * both {@link IssueType#ORPHANED} and {@link IssueType#NEW_RESOURCE}.
 *
 * <p>Uses Java 25 pattern matching for switch across all ResourceCategory types.
 */
public class GovernanceTagRule implements OptimizationRule {

    private static final Logger log = LoggerFactory.getLogger(GovernanceTagRule.class);

    /** Mandatory tag keys — case-insensitive lookup. */
    private static final List<String> MANDATORY_TAGS = List.of(
            "environment",
            "owner",
            "costcenter");

    @Override
    public Optional<OptimizationFinding> evaluate(ResourceCategory category) {
        return switch (category) {
            case ResourceCategory.VirtualMachine(var r)  when hasMissingMandatoryTags(r) -> Optional.of(buildFinding(r));
            case ResourceCategory.StorageAccount(var r)  when hasMissingMandatoryTags(r) -> Optional.of(buildFinding(r));
            case ResourceCategory.NetworkInterface(var r) when hasMissingMandatoryTags(r) -> Optional.of(buildFinding(r));
            case ResourceCategory.Other(var r)             when hasMissingMandatoryTags(r) -> Optional.of(buildFinding(r));
            default -> Optional.empty();
        };
    }

    /**
     * Returns {@code true} when the resource lacks the mandatory governance
     * tags — regardless of whether the tag map is {@code null}, empty, or
     * simply missing specific keys.
     *
     * <p>Azure resources (especially non-standard types like
     * {@code microsoft.alertsmanagement/smartdetectoralertrules}) often
     * return {@code null} tags. This method treats that identically to
     * an empty map so that the rule fires universally.
     */
    private boolean hasMissingMandatoryTags(AzureResource resource) {
        if (resource.tags() == null || resource.tags().isEmpty()) {
            return true;
        }

        var missing = MANDATORY_TAGS.stream()
                .filter(mandatory ->
                        resource.tags().keySet().stream()
                                .noneMatch(key -> key.equalsIgnoreCase(mandatory)))
                .toList();

        if (!missing.isEmpty()) {
            log.debug("[GovernanceTag] '{}' is missing tag(s): {}", resource.name(), missing);
            return true;
        }

        return false;
    }

    /**
     * Builds a finding with a precise description of which tags are absent.
     *
     * <p>Null-safe: when the tag map itself is absent the message lists
     * all mandatory tags.
     */
    private OptimizationFinding buildFinding(AzureResource resource) {
        // No tag map at all → every mandatory tag is missing
        if (resource.tags() == null || resource.tags().isEmpty()) {
            return new OptimizationFinding(
                    IssueType.GOVERNANCE_MISSING,
                    "Resource carries no tags at all. Required governance tags: "
                            + String.join(", ", MANDATORY_TAGS) + ".");
        }
        // Tags exist → list the specific keys that are absent
        var missing = MANDATORY_TAGS.stream()
                .filter(mandatory ->
                        resource.tags().keySet().stream()
                                .noneMatch(key -> key.equalsIgnoreCase(mandatory)))
                .toList();
        return new OptimizationFinding(
                IssueType.GOVERNANCE_MISSING,
                "Missing mandatory governance tag(s): " + String.join(", ", missing) + ".");
    }
}