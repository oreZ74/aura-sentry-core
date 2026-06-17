package de.orez.aura_sentry_core.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.model.BillingInfo;
import de.orez.aura_sentry_core.model.CostApiResult;
import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.OptimizationResult;
import de.orez.aura_sentry_core.model.ResourceState;
import de.orez.aura_sentry_core.optimization.OptimizationEngine;
import de.orez.aura_sentry_core.persistence.entity.ResourceCacheEntity;
import de.orez.aura_sentry_core.persistence.repository.ResourceCacheRepository;
import de.orez.aura_sentry_core.service.demo.DemoModeProvider;

/**
 * Orchestrates Azure-to-DB synchronisation with true upsert semantics.
 *
 * <h3>The ORPHANED-Bug Fix</h3>
 * <p>Before this service, every scan appended a new {@code scan_results}
 * row. Existing resources (e.g. a 2-day-old Application Insights with
 * {@code Environment=Dev}) retained their old {@code ORPHANED} flag
 * in the database because the append-only history never overwrote
 * the previous row.</p>
 *
 * <p>This service uses {@code resource_cache} as the <em>current-state</em>
 * table. For each resource returned by the Azure scan:</p>
 * <ul>
 *   <li><b>Found?</b> Load the existing {@link ResourceCacheEntity} by
 *       {@code azureId}, overwrite {@code flag}, {@code state} and
 *       {@code cost} with the fresh scan values.</li>
 *   <li><b>Missing?</b> Create a brand-new {@code ResourceCacheEntity}.</li>
 * </ul>
 *
 * <p>The {@code flag} is derived from the {@link OptimizationEngine}
 * findings. A resource younger than 7 days correctly receives
 * {@link IssueType#NEW_RESOURCE} instead of {@link IssueType#ORPHANED}.</p>
 *
 * <h3>Performance</h3>
 * Costs are resolved in a single batch call to
 * {@link AzureCostService#fetchCostsByResource()} before the upsert loop,
 * eliminating any N+1 behaviour towards Azure.
 */
@Service
@RequiredArgsConstructor
public class AzureScanService {

    private static final Logger log = LoggerFactory.getLogger(AzureScanService.class);

    /**
     * Severity priority map for {@link IssueType}. Lower numeric value = higher severity.
     * <p>
     * Actionable misconfigurations (GOVERNANCE_MISSING, ORPHANED, SECURITY_RISK, etc.)
     * must always take precedence over informational flags like {@code NEW_RESOURCE}.
     * {@code NEW_RESOURCE} is intentionally ranked lowest so it only becomes the final
     * flag when no other actionable issue exists — i.e. it replaces a premature
     * {@code ORPHANED} classification for resources younger than 7 days.
     */
    private static final Map<IssueType, Integer> SEVERITY_PRIORITY;
    static {
        var map = new EnumMap<IssueType, Integer>(IssueType.class);
        map.put(IssueType.SECURITY_RISK,      1);   // CRITICAL
        map.put(IssueType.GOVERNANCE_MISSING, 2);   // HIGH – outranks ORPHANED
        map.put(IssueType.POLICY_MISSING,     2);   // HIGH (same severity)
        map.put(IssueType.ORPHANED,           3);   // HIGH
        map.put(IssueType.OVERPROVISIONED,    4);   // MEDIUM
        map.put(IssueType.UNDERUTILIZED,      5);   // MEDIUM
        map.put(IssueType.COST_OPTIMIZATION,  6);   // MEDIUM
        map.put(IssueType.NEW_RESOURCE,       7);   // LOW / INFO only
        map.put(IssueType.HEALTHY,            99);  // ignored by filter anyway
        SEVERITY_PRIORITY = Map.copyOf(map);
    }

    private final CloudProviderService cloudProviderService;
    private final OptimizationEngine optimizationEngine;
    private final AzureCostService azureCostService;
    private final ResourceCacheRepository resourceCacheRepository;
    private final DemoModeProvider demoModeProvider;

    /**
     * Performs a full Azure scan and writes the results into
     * {@code resource_cache} using upsert semantics.
     *
     * <p>Steps:</p>
     * <ol>
     *   <li>Fetch live resources from Azure.</li>
     *   <li>Run Guardian Logic ({@link OptimizationEngine}) to compute flags.</li>
     *   <li>Fetch real costs in <em>one</em> batch call.</li>
     *   <li>For each resource, find existing cache row by {@code azureId}.
     *       Overwrite fields; create new row if absent.</li>
     *   <li>{@code saveAll()} the batch.</li>
     * </ol>
     *
     * @return number of upserted resources
     */
    @Transactional
    public int syncFromAzure() {
        // ── 0. Demo Mode Guard ──
        // Demo mode is a pure in-memory bypass. It NEVER writes to the
        // database so that live Azure data is never polluted by mock data.
        if (demoModeProvider.isDemoMode()) {
            log.info("[AzureScan] DEMO MODE: sync is a no-op. Resources are served in-memory by ResourceCacheService.");
            return 0;
        }

        // ── 1. Fetch live Azure resources ──
        var azureResources = cloudProviderService.fetchResources();
        log.info("[AzureScan] Fetched {} resources from Azure", azureResources.size());

        // ── 2. Batch-fetch real costs (single API call) ──
        Map<String, Double> rawCosts = azureCostService.fetchCostsByResource();
        log.info("[AzureScan] Fetched {} cost entries from Azure Cost Management", rawCosts.size());

        Map<String, BillingInfo> billingMap = new HashMap<>();
        rawCosts.forEach((id, amount) -> billingMap.put(id, new BillingInfo(amount, "USD")));
        CostApiResult costApiResult = CostApiResult.success(billingMap);

        // ── 3. Run Guardian Logic WITH real cost data ──
        // This is the critical step: rules evaluate metrics, tags, state etc.
        // and produce IssueType flags (HEALTHY, ORPHANED, UNDERUTILIZED, ...).
        List<OptimizationResult> results = optimizationEngine.analyze(azureResources, costApiResult);

        long flaggedCount = results.stream()
                .filter(r -> r.findings().stream().anyMatch(f -> f.flag() != IssueType.HEALTHY))
                .count();
        log.info("[AzureScan] Guardian Logic produced {} non-HEALTHY resources out of {}",
                flaggedCount, results.size());

        // ── 4. Upsert loop: write EVERY OptimizationResult flag into resource_cache ──
        List<ResourceCacheEntity> upsertBatch = new ArrayList<>(results.size());
        Instant now = Instant.now();

        for (OptimizationResult result : results) {
            String azureId = result.resourceId();

            // Case-insensitive lookup; the DB handles the matching.
            Optional<ResourceCacheEntity> existing = resourceCacheRepository.findByAzureIdIgnoreCase(azureId);

            // New rows are always stored with a lowercased ID so the
            // database has a single, canonical representation.
            ResourceCacheEntity entity = existing.orElseGet(() ->
                    new ResourceCacheEntity(azureId.toLowerCase(), result.resourceName(),
                            result.resourceType(), result.resourceGroup(), result.state(),
                            result.sku(), result.region()));

            // Overwrite fields with fresh scan data
            entity.setName(result.resourceName());
            entity.setType(result.resourceType());
            entity.setResourceGroup(result.resourceGroup());
            entity.setState(result.state());
            entity.setSku(result.sku());
            entity.setRegion(result.region());
            entity.setScannedAt(now);
            entity.setUpdatedAt(now);

            // ── Resolve flag from findings by severity priority ──
            // Actionable misconfigurations (ORPHANED, GOVERNANCE_MISSING, etc.)
            // always outrank informational NEW_RESOURCE.
            // NEVER leave the previous flag untouched — always overwrite.
            IssueType resolvedFlag = result.findings().stream()
                    .filter(f -> f.flag() != IssueType.HEALTHY)
                    .min(Comparator.comparingInt(f ->
                            SEVERITY_PRIORITY.getOrDefault(f.flag(), 99)))
                    .map(OptimizationFinding::flag)
                    .orElse(IssueType.HEALTHY);

            entity.setFlag(resolvedFlag);

            // Resolve cost from batch map (0.0 fallback)
            double cost = rawCosts.getOrDefault(azureId.toLowerCase(), 0.0);
            entity.setCost(cost);

            // ── AUDIT: log the exact flag we are about to write ──
            List<String> rawFlags = result.findings().stream()
                    .map(f -> f.flag().name())
                    .toList();
            if (existing.isPresent()) {
                IssueType oldFlag = existing.get().getFlag();
                log.info("[AzureScan] UPDATE '{}' → oldFlag={} → newFlag={} (raw findings={})",
                        azureId, oldFlag, resolvedFlag, rawFlags);
            } else {
                log.info("[AzureScan] INSERT '{}' → flag={} (raw findings={})",
                        azureId, resolvedFlag, rawFlags);
            }

            upsertBatch.add(entity);
        }

        // ── 5. Persist batch ──
        resourceCacheRepository.saveAll(upsertBatch);
        log.info("[AzureScan] Upsert complete: {} resources written to resource_cache "
                + "({} updated, {} inserted)",
                upsertBatch.size(),
                upsertBatch.stream().filter(e -> e.getId() != null).count(),
                upsertBatch.stream().filter(e -> e.getId() == null).count());

        return upsertBatch.size();
    }
}