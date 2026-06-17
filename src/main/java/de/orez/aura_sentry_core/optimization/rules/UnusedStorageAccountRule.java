package de.orez.aura_sentry_core.optimization.rules;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.model.AzureResource;
import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.ResourceCategory;
import de.orez.aura_sentry_core.optimization.OptimizationRule;

/**
 * FinOps rule: flags unused or underutilised Azure Storage Accounts.
 *
 * <p>
 * A Storage Account is considered a data graveyard when its stored data
 * falls <b>below 1 KB</b> <b>or</b> recorded transactions fall <b>below
 * 1000</b>
 * in the last 7 days. In both cases the resource is burning cost without
 * delivering value.
 *
 * <p>
 * <b>Guard clause:</b> only fires for {@code StorageAccount}. All other
 * categories immediately return {@code Optional.empty()} so that PaaS
 * resources (App Insights, Logic Apps, etc.) are never touched by this
 * utilisation logic.
 *
 * <p>
 * Metrics are expected under the keys
 * {@value #METRIC_USED_CAPACITY} (bytes) and {@value #METRIC_TRANSACTIONS}
 * (count). Missing keys are treated as {@code 0.0} (real zero-utilisation),
 * never as unknown. The rule always makes a hard decision.
 */
public class UnusedStorageAccountRule implements OptimizationRule {

    private static final Logger log = LoggerFactory.getLogger(UnusedStorageAccountRule.class);

    static final String METRIC_USED_CAPACITY = "UsedCapacity";
    static final String METRIC_TRANSACTIONS = "Transactions";

    private static final double MIN_CAPACITY_BYTES = 1024.0; // 1 KB
    private static final double MIN_TRANSACTIONS = 1000.0; // 1000 Tx / 7 Tage

    private static final OptimizationFinding ORPHANED_FINDING = new OptimizationFinding(
            IssueType.ORPHANED,
            "Storage Account holds less than 1 KB — effectively empty. "
                    + "Consider deletion to eliminate unnecessary cost.");

    private static final OptimizationFinding UNDERUTILIZED_FINDING = new OptimizationFinding(
            IssueType.UNDERUTILIZED,
            "Storage Account recorded fewer than 1000 transactions in the last 7 days — "
                    + "data exists but is cold. Consider archiving to Cool or Archive tier.");

    @Override
    public Optional<OptimizationFinding> evaluate(ResourceCategory category) {
        return switch (category) {
            case ResourceCategory.StorageAccount(var r) -> evaluateStorageAccount(r);
            default -> Optional.empty();
        };
    }

    private Optional<OptimizationFinding> evaluateStorageAccount(AzureResource resource) {
        Map<String, Double> metrics = resource.metrics();

        // Hard-FinOps: Keine frühe Rückkehr bei "leerer" Map.
        // AzureMonitorMetricsService garantiert, dass beide Keys mit 0.0 vorhanden
        // sind.
        if (metrics == null) {
            return Optional.empty();
        }

        // getOrDefault eliminiert jede null-Pointer-Gefahr.
        // Fehlende Keys = echte 0.0-Nutzung (ORPHANED / UNDERUTILIZED).
        double usedCapacity = metrics.getOrDefault(METRIC_USED_CAPACITY, 0.0);
        double transactions = metrics.getOrDefault(METRIC_TRANSACTIONS, 0.0);

        log.debug("Storage Account '{}': UsedCapacity={}, Transactions={}",
                resource.name(), usedCapacity, transactions);

        // 1. Below capacity threshold → ORPHANED (data graveyard)
        if (usedCapacity < MIN_CAPACITY_BYTES) {
            log.warn("Storage Account '{}' flagged ORPHANED: {} bytes (Threshold: {})",
                    resource.name(), usedCapacity, MIN_CAPACITY_BYTES);
            return Optional.of(ORPHANED_FINDING);
        }

        // 2. Below transaction threshold → UNDERUTILIZED (cold data)
        if (transactions < MIN_TRANSACTIONS) {
            log.debug("Storage Account '{}' flagged UNDERUTILIZED: {} transactions (Threshold: {})",
                    resource.name(), transactions, MIN_TRANSACTIONS);
            return Optional.of(UNDERUTILIZED_FINDING);
        }

        return Optional.empty();
    }
}
