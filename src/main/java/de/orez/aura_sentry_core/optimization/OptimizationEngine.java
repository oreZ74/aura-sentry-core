package de.orez.aura_sentry_core.optimization;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.model.AzureResource;
import de.orez.aura_sentry_core.model.CostApiResult;
import de.orez.aura_sentry_core.model.CostProfile;
import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.OptimizationResult;
import de.orez.aura_sentry_core.model.ResourceCategory;
import de.orez.aura_sentry_core.optimization.rules.DevVmWasteRule;
import de.orez.aura_sentry_core.optimization.rules.GovernanceTagRule;
import de.orez.aura_sentry_core.optimization.rules.OrphanedResourceRule;
import de.orez.aura_sentry_core.optimization.rules.UnusedStorageAccountRule;
import de.orez.aura_sentry_core.service.PricingEngine;

/**
 * Core component of the Guardian Logic.
 * Applies all registered OptimizationRules to each AzureResource in parallel.
 *
 * <h3>Cost analysis</h3>
 * Delegates to {@link CostResolutionChain} which enforces strict priority:
 * <ol>
 *   <li><b>Actual Billing</b> (Azure Cost Management API) — a cost of
 *       {@code 0.0} is authoritative and never overwritten</li>
 *   <li><b>PricingEngine</b> — heuristic estimation, only when the API
 *       has no entry for the resource at all</li>
 *   <li><b>Unavailable</b> — terminal sentinel when no data is obtainable</li>
 * </ol>
 *
 * <p>Each {@link OptimizationResult} carries a {@link CostProfile} that
 * transparently communicates the cost source throughout the entire pipeline.
 *
 * <p>Modern Java 25:
 * <ul>
 *   <li>Sealed classes for ResourceCategory pattern matching</li>
 *   <li>Virtual threads for concurrent rule evaluation</li>
 *   <li>Records for type-safe OptimizationResult aggregation</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class OptimizationEngine {

    private static final Logger log = LoggerFactory.getLogger(OptimizationEngine.class);

    private final List<OptimizationRule> rules = List.of(
            new OrphanedResourceRule(),
            new GovernanceTagRule(),
            new DevVmWasteRule(),
            new UnusedStorageAccountRule());

    private final PricingEngine pricingEngine;
    private final CostResolutionChain costResolutionChain;

    /**
     * Analyses resources using static {@link PricingEngine} estimates.
     * Use {@link #analyze(List, CostApiResult)} for real billing data.
     */
    public List<OptimizationResult> analyze(List<AzureResource> resources) {
        return analyze(resources, CostApiResult.success(Map.of()));
    }

    /**
     * Analyses resources with actual billing data from Azure Cost Management.
     *
     * @param resources     the Azure resources to analyse
     * @param costApiResult wrapped billing map with success flag; the flag
     *                      is {@code false} when the API failed (rate limit,
     *                      auth error) – the PricingEngine is NOT consulted
     *                      in that case to avoid treating estimates as real costs
     * @return one {@link OptimizationResult} per resource
     */
    public List<OptimizationResult> analyze(List<AzureResource> resources, CostApiResult costApiResult) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<OptimizationResult>> futures = resources.stream()
                    .map(resource -> CompletableFuture.supplyAsync(
                            () -> evaluateResource(resource, costApiResult), executor))
                    .toList();

            return futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        }
    }

    private OptimizationResult evaluateResource(AzureResource resource, CostApiResult costApiResult) {
        ResourceCategory category = ResourceCategory.of(resource);

        // ── Evaluate EVERY rule; no early-exit. Optional.empty() means "pass" ──
        var findings = rules.stream()
                .map(rule -> {
                    Optional<OptimizationFinding> result = rule.evaluate(category);
                    log.debug("Rule {} for '{}': {}",
                            rule.getClass().getSimpleName(), resource.name(),
                            result.isPresent() ? result.get().flag() : "PASS");
                    return result;
                })
                .flatMap(Optional::stream)
                .toList();

        log.info("[AuraSentry Read-Only FinOps Auditor] Resource '{}': total findings={}, flags={}",
                resource.name(), findings.size(),
                findings.stream().map(f -> f.flag().name()).toList());

        // ── Cost: Chain-of-Responsibility resolves the authoritative profile ──
        CostProfile costProfile = costResolutionChain.resolve(resource, costApiResult);

        // ── Savings: you can't save money that isn't being spent ──
        double monthlySavings = 0.0;
        boolean isOrphaned = findings.stream()
                .anyMatch(f -> f.flag() == IssueType.ORPHANED);
        boolean hasIssues = !findings.isEmpty();

        if (costProfile instanceof CostProfile.ActualCost ac && ac.amount() <= 0.0) {
            // Free-tier / idle — no monetary savings possible.
            monthlySavings = 0.0;
            log.debug("Savings for '{}': {}0.00 (actual cost is {}0.00)",
                    resource.name(), costProfile.currencySymbol(), costProfile.currencySymbol());
        } else if (costProfile.amount() <= 0.0) {
            monthlySavings = 0.0;
        } else if (isOrphaned) {
            monthlySavings = costProfile.amount();
        } else if (hasIssues) {
            monthlySavings = pricingEngine.calculateMonthlySavings(resource);
        }

        log.debug("Optimization analysis for '{}': cost={}{} [{}], savings={}{}",
                resource.name(), costProfile.currencySymbol(), costProfile.amount(),
                costProfile.source(), costProfile.currencySymbol(), monthlySavings);

        if (findings.isEmpty()) {
            return OptimizationResult.healthy(resource, costProfile);
        }

        return new OptimizationResult(
                resource.id(),
                resource.name(),
                resource.type(),
                resource.resourceGroup(),
                resource.state(),
                findings,
                costProfile.amount(),
                monthlySavings,
                costProfile.currencySymbol(),
                costProfile.source(),
                resource.sku(),
                resource.region(),
                resource.tags());
    }
}
