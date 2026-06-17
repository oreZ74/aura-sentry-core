package de.orez.aura_sentry_core.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized pricing configuration loaded from
 * {@code application.properties} or {@code application.yml}.
 *
 * <p>Replaces hardcoded constants in {@code PricingEngine} so that
 * SKU-to-cost mappings can be tuned without recompilation.
 *
 * <h3>Property namespace</h3>
 * {@code aura-sentry.pricing.*}
 */
@ConfigurationProperties(prefix = "aura-sentry.pricing")
public record PricingConfig(
        Map<String, Double> regionMultipliers,
        Map<String, Double> storageRates,
        Map<String, Double> computeRates,
        Map<String, Double> databaseRates,
        Map<String, Double> networkRates,
        Map<String, Double> diskRates,
        List<PricingRule> customRules,
        double defaultUnknownCost,
        String defaultCurrency,
        boolean enabled
) {

    /**
     * A declarative pricing rule for a specific resource-type / SKU combination.
     */
    public record PricingRule(
            String resourceType,
            String skuPattern,
            double monthlyCost,
            String unit,
            String description
    ) {}

    public double regionMultiplier(String region) {
        if (region == null || regionMultipliers == null) return 1.0;
        String key = region.toLowerCase().replaceAll("[^a-z0-9]", "");
        return regionMultipliers.getOrDefault(key, 1.0);
    }
}
