package de.orez.aura_sentry_core.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lazily resolves actual storage usage from Azure Monitor metrics
 * so the {@code PricingEngine} can use real GiB values instead of
 * hardcoded constants.
 *
 * <p>Each resource's {@code UsedCapacity} is fetched at most once
 * per JVM lifetime (cached in a {@code ConcurrentHashMap}) because
 * storage usage changes on the order of days, not seconds.
 *
 * <p>When the metric is unavailable (e.g. resource does not support
 * {@code UsedCapacity}, credentials missing, API error), {@code NaN}
 * is returned and the caller must fall back to a safe default.
 */
@Component
public class StorageUsageProvider {

    private static final Logger log = LoggerFactory.getLogger(StorageUsageProvider.class);

    private final AzureMonitorService monitorService;
    private final Map<String, Double> cache = new ConcurrentHashMap<>();

    public StorageUsageProvider(AzureMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    /**
     * Returns the actual used capacity in GiB for the given resource,
     * or {@code NaN} if the metric cannot be obtained.
     */
    public double getUsedGb(String resourceId) {
        return cache.computeIfAbsent(resourceId, id -> {
            double gb = monitorService.fetchStorageUsedCapacity(id);
            if (Double.isNaN(gb)) {
                log.debug("[StorageUsage] UsedCapacity unavailable for '{}' → NaN", id);
            }
            return gb;
        });
    }

    /** For testability / selective cache eviction. */
    public void evict(String resourceId) {
        cache.remove(resourceId);
    }

    public int cacheSize() {
        return cache.size();
    }
}
