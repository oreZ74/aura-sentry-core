package de.orez.aura_sentry_core.config;

import java.time.Duration;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.cache.caffeine.CaffeineCacheManager;

/**
 * Spring Cache configuration with Caffeine.
 *
 * <p>Cost Management API data changes at most once per day at Microsoft's side,
 * so a 30-minute cache virtually eliminates HTTP 429 rate limits from repeated
 * dashboard reloads while staying close enough to real billing data.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** How long cost data is considered fresh for the legacy costManagement cache. */
    private static final Duration COST_TTL = Duration.ofMinutes(30);

    /** Strict 15-minute TTL for the azureCostMapCache to prevent HTTP 429 rate limits
     *  while staying reasonably close to real Azure billing data. */
    private static final Duration AZURE_COST_MAP_TTL = Duration.ofMinutes(15);

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("costManagement", "azureCostMapCache");

        // Legacy cache (kept for AzureCostManagementService backward compatibility)
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(COST_TTL)
                .maximumSize(10)
                .recordStats());

        // Dedicated cache for AzureCostService.fetchCostsByResource() Map<String, Double>
        // with stricter 15-minute eviction to balance freshness vs. API rate limits.
        Caffeine<Object, Object> azureCostMapCache = Caffeine.newBuilder()
                .expireAfterWrite(AZURE_COST_MAP_TTL)
                .maximumSize(1)
                .recordStats();

        manager.registerCustomCache("azureCostMapCache", azureCostMapCache.build());
        return manager;
    }
}
