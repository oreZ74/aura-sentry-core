package de.orez.aura_sentry_core.service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.resources.models.GenericResource;

import de.orez.aura_sentry_core.model.AzureResource;
import de.orez.aura_sentry_core.model.ResourceState;

/**
 * Azure resource fetcher – refactored for dynamic credentials.
 *
 * Changes from v1:
 * <ul>
 *   <li>Credentials are fetched on-demand from the encrypted database store
 *       instead of a singleton {@link AzureResourceManager} bean</li>
 *   <li>{@code AzureResourceManager} is created per-scan via
 *       {@link AzureResourceManagerFactory}</li>
 *   <li>A {@link Semaphore} caps concurrent Azure mapping calls (max 20)
 *       to protect the Azure Management API from overload during large
 *       subscription scans</li>
 *   <li>Virtual threads via {@code Executors.newVirtualThreadPerTaskExecutor()}
 *       handle the parallel mapping of {@link GenericResource} to
 *       {@link AzureResource} without blocking platform threads</li>
 *   <li>Placeholder detection delegated to {@link CredentialResolutionService}</li>
 * </ul>
 */
@Service
public class AzureResourceService {

    private static final Logger log = LoggerFactory.getLogger(AzureResourceService.class);
    private static final int MAX_CONCURRENT_AZURE_CALLS = 20;

    private final AzureResourceManagerFactory armFactory;
    private final CredentialResolutionService credentialResolutionService;
    private final AzureResourceGraphService resourceGraphService;
    private final AzureMonitorMetricsService metricsService;
    private final Semaphore azureSemaphore = new Semaphore(MAX_CONCURRENT_AZURE_CALLS);

    public AzureResourceService(AzureResourceManagerFactory armFactory,
            CredentialResolutionService credentialResolutionService,
            AzureResourceGraphService resourceGraphService,
            AzureMonitorMetricsService metricsService) {
        this.armFactory = armFactory;
        this.credentialResolutionService = credentialResolutionService;
        this.resourceGraphService = resourceGraphService;
        this.metricsService = metricsService;
    }

    public List<AzureResource> fetchResources() {
        var creds = credentialResolutionService.resolveAndValidateCredentials();
        AzureResourceManager arm = armFactory.create(
                creds.tenantId(), creds.clientId(), creds.clientSecret(), creds.subscriptionId());

        Map<String, Instant> creationTimes = resourceGraphService.fetchCreationTimes();
        // Tags from Resource Graph as fallback – ARM genericResources().list()
        // often omits tags for non-standard resource types.
        Map<String, Map<String, String>> graphTags = resourceGraphService.fetchTags();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<AzureResource>> futures = arm.genericResources().list().stream()
                    .map(resource -> executor.submit(() -> {
                        try {
                            azureSemaphore.acquire();
                            return mapToAzureResource(resource, creationTimes, graphTags);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Virtual thread interrupted during Azure fetch", e);
                        } finally {
                            azureSemaphore.release();
                        }
                    }))
                    .toList();

            return futures.stream()
                    .map(this::getResult)
                    .toList();
        }
    }

    private AzureResource mapToAzureResource(GenericResource resource,
                                             Map<String, Instant> creationTimes,
                                             Map<String, Map<String, String>> graphTags) {
        // Primary source: GenericResource.tags() (fast, SDK-deserialised).
        // Fallback: Resource Graph (reliable for non-standard resource types).
        Map<String, String> armTags = resource.tags() != null ? resource.tags() : null;
        Map<String, String> graphTagMap = graphTags.get(resource.id().toLowerCase());
        Map<String, String> tags;
        if (armTags != null && !armTags.isEmpty()) {
            tags = Collections.unmodifiableMap(armTags);
        } else if (graphTagMap != null && !graphTagMap.isEmpty()) {
            tags = Collections.unmodifiableMap(graphTagMap);
        } else {
            tags = Map.of();
        }

        log.info("[AzureResourceService] Resource '{}' (type={}): ARM-tags={}, Graph-tags={}, final-tags={}",
                resource.name(), resource.type(),
                armTags != null ? armTags.size() + " keys" : "NULL",
                graphTagMap != null ? graphTagMap.size() + " keys" : "NULL",
                tags.size() + " keys");

        String region = extractRegion(resource);
        String sku = extractSku(resource);

        log.debug("Mapped resource '{}' (type: {}, region: {}, sku: {}, tags: {})",
                resource.name(), resource.type(), region, sku, tags.size());

        // ── Fetch Azure Monitor metrics for Storage Accounts only ──
        // VMs use hypervisor-level CPU/RAM (different pipeline), PaaS has no
        // meaningful capacity metrics.  Error handling is defensive: if the
        // metrics API fails we fall back to an empty map so the scan never
        // crashes because of a single unresponsive resource.
        Map<String, Double> metrics = Map.of();
        String resourceType = resource.type() == null ? "" : resource.type().toLowerCase();
        if (resourceType.contains("microsoft.storage/storageaccounts")) {
            try {
                metrics = metricsService.fetchStorageMetrics(resource.id());
                log.info("[AzureResourceService] Metrics for Storage Account '{}': {}",
                        resource.name(), metrics);
            } catch (Exception e) {
                log.warn("[AzureResourceService] Failed to fetch metrics for '{}': {} -- continuing with empty metrics",
                        resource.name(), e.getMessage());
            }
        }

        return new AzureResource(
                resource.id(),
                resource.name(),
                resource.type(),
                resource.resourceGroupName(),
                tags,
                resolveState(resource),
                resourceGraphService.resolveCreatedAt(resource.id(), creationTimes),
                sku,
                region,
                metrics);
    }

    private String extractSku(GenericResource resource) {
        try {
            var sku = resource.sku();
            if (sku != null) {
                String name = sku.name();
                String tier = sku.tier();
                if (name != null && tier != null) {
                    return name + " / " + tier;
                }
                if (name != null) {
                    return name;
                }
                if (tier != null) {
                    return tier;
                }
            }
        } catch (Exception e) {
            log.debug("SKU extraction failed for '{}': {}", resource.name(), e.getMessage());
        }
        return "N/A";
    }

    private String extractRegion(GenericResource resource) {
        try {
            String region = resource.regionName();
            if (region != null && !region.isBlank()) {
                return region;
            }
            if (resource.tags() != null) {
                String regionTag = resource.tags().get("region");
                if (regionTag != null && !regionTag.isBlank()) {
                    return regionTag;
                }
            }
        } catch (Exception e) {
            log.debug("Region extraction failed for '{}': {}", resource.name(), e.getMessage());
        }
        return "Unknown";
    }

    private ResourceState resolveState(GenericResource resource) {
        return ResourceState.RUNNING;
    }

    private AzureResource getResult(Future<AzureResource> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Virtual thread interrupted during resource fetch", e);
        } catch (Exception e) {
            log.error("Failed to map Azure resource", e);
            throw new IllegalStateException("Error fetching Azure resource", e);
        }
    }
}
