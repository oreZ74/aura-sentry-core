package de.orez.aura_sentry_core.controller;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;

import de.orez.aura_sentry_core.controller.dto.AnalysisResponse;
import de.orez.aura_sentry_core.controller.dto.ResourceCacheDto;
import de.orez.aura_sentry_core.controller.dto.SyncResponse;
import de.orez.aura_sentry_core.model.BillingInfo;
import de.orez.aura_sentry_core.model.CostApiResult;
import de.orez.aura_sentry_core.model.OptimizationResult;
import de.orez.aura_sentry_core.optimization.OptimizationEngine;
import de.orez.aura_sentry_core.persistence.ScanPersistenceService;
import de.orez.aura_sentry_core.service.AzureCostService;
import de.orez.aura_sentry_core.service.AzureScanService;
import de.orez.aura_sentry_core.service.CloudProviderService;
import de.orez.aura_sentry_core.service.ResourceCacheService;
import de.orez.aura_sentry_core.service.demo.DemoModeProvider;

@RestController
@RequestMapping("/api/v1/optimization")
@RequiredArgsConstructor
public class OptimizationController {

    private static final Logger log = LoggerFactory.getLogger(OptimizationController.class);

    private final CloudProviderService cloudProviderService;
    private final OptimizationEngine optimizationEngine;
    private final ScanPersistenceService scanPersistenceService;
    private final AzureScanService azureScanService;
    private final ResourceCacheService resourceCacheService;
    private final AzureCostService azureCostService;
    private final DemoModeProvider demoModeProvider;

    /**
     * Analyses all Azure resources in the configured subscription
     * and returns the Guardian Logic findings as structured JSON.
     *
     * <p>
     * Loads real billing data from Azure Cost Management API
     * (month-to-date) before running Guardian Logic rules.
     * Falls back to static PricingEngine estimates if the
     * Cost API is unavailable or no permission is granted.
     *
     * GET /api/v1/optimization/analyze
     */
    @GetMapping("/analyze")
    public ResponseEntity<AnalysisResponse> analyze(
            @RequestHeader(value = "X-Demo-Mode", defaultValue = "false") boolean demoMode) {
        demoModeProvider.setDemoMode(demoMode);
        try {
            // ── Load actual billing data (skipped in demo mode) ──
            CostApiResult costApiResult = CostApiResult.failure();
            if (!demoMode) {
                Map<String, Double> costs = azureCostService.fetchCostsByResource();
                Map<String, BillingInfo> billingMap = new HashMap<>();
                costs.forEach((id, amount) -> billingMap.put(id, new BillingInfo(amount, "USD")));
                costApiResult = CostApiResult.success(billingMap);
                log.info("[Optimization] Fetched {} cost entries from Azure Cost Management",
                        costApiResult.costsByResource().size());
            }

            var resources = cloudProviderService.fetchResources();
            List<OptimizationResult> results = optimizationEngine.analyze(resources, costApiResult);

            var now = Instant.now();
            var response = new AnalysisResponse(
                    now,
                    resources.size(),
                    AnalysisResponse.AnalysisSummary.from(results),
                    results);

            scanPersistenceService.persistAsync(results, now);

            return ResponseEntity.ok(response);
        } finally {
            demoModeProvider.clear();
        }
    }

    @GetMapping("/history")
    public ResponseEntity<List<OptimizationResult>> history() {
        return ResponseEntity.ok(scanPersistenceService.getRecentScans());
    }

    /**
     * Returns all resources from the {@code resource_cache} table with
     * eagerly refreshed Azure costs.
     *
     * <p>This endpoint is the primary data source for the dashboard table.
     * It performs a single batch cost-refresh from Azure and returns the
     * current-state DTOs. No live Azure resource scan is triggered.
     *
     * GET /api/v1/optimization/cached-resources
     */
    @GetMapping("/cached-resources")
    public ResponseEntity<List<ResourceCacheDto>> cachedResources() {
        List<ResourceCacheDto> resources = resourceCacheService.getAllResources();
        log.info("[OptimizationController] Returned {} cached resources", resources.size());
        return ResponseEntity.ok(resources);
    }

    @CacheEvict(value = "azureCostMapCache", allEntries = true)
    @GetMapping("/sync")
    public ResponseEntity<SyncResponse> sync() {
        log.info("[OptimizationController] Manual sync triggered via /sync");
        int upsertedCount = azureScanService.syncFromAzure();
        Instant now = Instant.now();
        SyncResponse response = new SyncResponse(
                upsertedCount,
                upsertedCount + " resources synced successfully from Azure.",
                now);
        log.info("[OptimizationController] Sync completed at {} – {} resources upserted", now, upsertedCount);
        return ResponseEntity.ok(response);
    }
}
