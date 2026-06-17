package de.orez.aura_sentry_core.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.controller.dto.ResourceCacheDto;
import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.OptimizationResult;
import de.orez.aura_sentry_core.optimization.OptimizationEngine;
import de.orez.aura_sentry_core.persistence.entity.ResourceCacheEntity;
import de.orez.aura_sentry_core.persistence.repository.ResourceCacheRepository;
import de.orez.aura_sentry_core.service.demo.DemoModeProvider;

/**
 * Service for the resource cache table with eager Azure cost loading.
 *
 * <h3>Demo Mode In-Memory Bypass</h3>
 * When {@link DemoModeContext#isDemoMode()} is {@code true} this service
 * completely bypasses the database. It loads the mock resources from
 * {@code demo-resources.json}, runs the {@link OptimizationEngine} rules
 * live in-memory, and returns the DTOs directly.  The real
 * {@code resource_cache} table is never read or written, preventing
 * demo data from polluting live Azure data.
 *
 * <h3>Eager Loading Design (Live Mode)</h3>
 * <ul>
 *   <li><b>Single batch call:</b> {@code fetchCostsByResource()} is called
 *       exactly once per request, returning a {@code Map<String, Double>}
 *       of all resource costs. No N+1 API calls.</li>
 *   <li><b>Graceful fallback:</b> If the Cost API fails, the map is empty
 *       and every resource keeps its previously cached cost (or 0.0).</li>
 *   <li><b>Persistent cache:</b> After mapping, the updated entities are
 *       written back to {@code resource_cache} via {@code saveAll()} so
 *       subsequent reads are fast and offline-capable.</li>
 * </ul>
 */
@Service
public class ResourceCacheService {

    private static final Logger log = LoggerFactory.getLogger(ResourceCacheService.class);

    private final ResourceCacheRepository resourceCacheRepository;
    private final AzureCostService azureCostService;
    private final CloudProviderService cloudProviderService;
    private final OptimizationEngine optimizationEngine;
    private final DemoModeProvider demoModeProvider;

    public ResourceCacheService(ResourceCacheRepository resourceCacheRepository,
                                  AzureCostService azureCostService,
                                  CloudProviderService cloudProviderService,
                                  OptimizationEngine optimizationEngine,
                                  DemoModeProvider demoModeProvider) {
        this.resourceCacheRepository = resourceCacheRepository;
        this.azureCostService = azureCostService;
        this.cloudProviderService = cloudProviderService;
        this.optimizationEngine = optimizationEngine;
        this.demoModeProvider = demoModeProvider;
    }

    /**
     * Returns all resources for the dashboard table.
     *
     * <p><b>Demo mode:</b> loads {@code demo-resources.json}, evaluates
     * rules in-memory, and returns DTOs <em>without touching the DB</em>.
     * <p><b>Live mode:</b> reads from {@code resource_cache}, refreshes costs
     * from Azure Cost Management, persists updates, and returns DTOs.
     *
     * @return list of DTOs, never {@code null}; empty list if nothing
     *         is available.
     */
    @Transactional
    public List<ResourceCacheDto> getAllResources() {
        // ── Option A: In-Memory Bypass for Demo Mode ──
        // NEVER touches the database. Rules are evaluated live against
        // the mock JSON and DTOs are returned directly.
        if (demoModeProvider.isDemoMode()) {
            log.info("[ResourceCache] DEMO BYPASS: evaluating demo resources in-memory, skipping DB");

            var resources = cloudProviderService.fetchResources();
            List<OptimizationResult> results = optimizationEngine.analyze(resources);

            long nonHealthy = results.stream()
                    .filter(r -> r.findings().stream()
                            .anyMatch(f -> f.flag() != IssueType.HEALTHY))
                    .count();
            log.info("[ResourceCache] DEMO BYPASS: {} resources evaluated, {} non-HEALTHY",
                    results.size(), nonHealthy);

            return results.stream()
                    .map(this::toDemoDto)
                    .toList();
        }

        // ── Live mode: read from DB cache ──
        List<ResourceCacheEntity> cached = resourceCacheRepository.findAllByOrderByUpdatedAtDesc();

        if (cached.isEmpty()) {
            log.info("[ResourceCache] Cache is empty – no eager cost refresh possible. "
                    + "Trigger /api/v1/optimization/sync first.");
            return List.of();
        }

        // ── Single batch call to Azure (prevents N+1) ──
        Map<String, Double> realCosts = azureCostService.fetchCostsByResource();
        log.info("[ResourceCache] Eager-loaded {} cost entries for {} cached resources",
                realCosts.size(), cached.size());

        // ── Map costs onto cached entities ──
        for (ResourceCacheEntity entity : cached) {
            String lowerId = entity.getAzureId().toLowerCase();
            double freshCost = realCosts.getOrDefault(lowerId, 0.0);
            entity.setCost(freshCost);
        }

        // ── Persist updated batch ──
        List<ResourceCacheEntity> saved = resourceCacheRepository.saveAll(cached);
        log.info("[ResourceCache] Updated {} cached resources with fresh costs", saved.size());

        return saved.stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Maps an in-memory {@link OptimizationResult} to a {@link ResourceCacheDto}
     * for demo-mode display. No database ID exists for mock data.
     */
    private ResourceCacheDto toDemoDto(OptimizationResult r) {
        IssueType primaryFlag = r.findings().stream()
                .filter(f -> f.flag() != IssueType.HEALTHY)
                .findFirst()
                .map(OptimizationFinding::flag)
                .orElse(IssueType.HEALTHY);

        return new ResourceCacheDto(
                null, // no DB id for ephemeral demo data
                r.resourceId(),
                r.resourceName(),
                r.resourceType(),
                r.resourceGroup(),
                r.state(),
                primaryFlag,
                r.estimatedMonthlyCost(),
                r.currencySymbol(),
                r.sku(),
                r.region(),
                Instant.now(),
                Instant.now());
    }

    private ResourceCacheDto toDto(ResourceCacheEntity e) {
        return new ResourceCacheDto(
                e.getId(),
                e.getAzureId(),
                e.getName(),
                e.getType(),
                e.getResourceGroup(),
                e.getState(),
                e.getFlag(),
                e.getCost() != null ? e.getCost() : 0.0,
                e.getCurrency(),
                e.getSku(),
                e.getRegion(),
                e.getScannedAt(),
                e.getUpdatedAt());
    }
}