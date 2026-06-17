package de.orez.aura_sentry_core.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.orez.aura_sentry_core.config.PricingConfig;
import de.orez.aura_sentry_core.model.AzureResource;

/**
 * Dynamic Pricing Engine for Azure resource cost analysis.
 *
 * <p>Implements FinOps-driven cost estimation based on:
 * <ul>
 *   <li>Resource type (Storage, VM, Database, etc.)</li>
 *   <li>SKU/Tier (Premium, Standard, Basic, etc.)</li>
 *   <li>Region/Location (premium regions cost more)</li>
 *   <li>Redundancy options (GRS vs LRS, etc.)</li>
 * </ul>
 *
 * <p>All base prices are resolved from {@link PricingConfig} (YAML/properties),
 * making cost models tunable without recompilation.
 *
 * <p>Uses Java 25 Pattern Matching and Records for concise, type-safe logic.
 * All pricing is monthly in USD; adjust calibration in {@code application.yml}
 * to match your Azure pricing.
 */
@Service
public class PricingEngine {

    private static final Logger log = LoggerFactory.getLogger(PricingEngine.class);

    /**
     * Fallback regional multipliers used when no configuration is present.
     * West Europe = 1.0 baseline.
     */
    private static final Map<String, Double> DEFAULT_REGION_MULTIPLIERS = Map.ofEntries(
            Map.entry("westeurope", 1.0),
            Map.entry("northeurope", 0.95),
            Map.entry("eastus", 0.90),
            Map.entry("westus", 0.95),
            Map.entry("eastus2", 0.90),
            Map.entry("centralus", 0.88),
            Map.entry("japaneast", 1.15),
            Map.entry("eastasia", 1.10),
            Map.entry("southeastasia", 1.05),
            Map.entry("australiaeast", 1.10),
            Map.entry("brazilsouth", 1.20));

    /** Per-GB rates for storage SKU tiers. */
    private static final Map<String, Double> DEFAULT_STORAGE_RATES = Map.of(
            "PREMIUM", 0.15,
            "GRS", 0.035,
            "LRS", 0.023);

    /** Monthly base costs for compute SKU families. */
    private static final Map<String, Double> DEFAULT_COMPUTE_RATES = Map.ofEntries(
            Map.entry("_B1", 15.0),
            Map.entry("_B2", 30.0),
            Map.entry("_D2", 95.0),
            Map.entry("_D4", 190.0),
            Map.entry("_D8", 380.0),
            Map.entry("_E4", 290.0),
            Map.entry("_E8", 580.0));

    /** Monthly base costs for PostgreSQL tiers. */
    private static final Map<String, Double> DEFAULT_DATABASE_RATES = Map.of(
            "B_", 30.0,
            "GP_", 200.0,
            "MO_", 400.0,
            "D", 200.0,
            "E", 400.0);

    /** Monthly base costs for network resources. */
    private static final Map<String, Double> DEFAULT_NETWORK_RATES = Map.of(
            "static", 3.0,
            "dynamic", 0.0,
            "standard", 16.0);

    /** Monthly base costs for managed disks. */
    private static final Map<String, Double> DEFAULT_DISK_RATES = Map.of(
            "ULTRA", 200.0,
            "PREMIUM", 35.0,
            "STANDARD", 16.0);

    private static final double DEFAULT_UNKNOWN_COST = 5.0;
    private static final double DEFAULT_COMPUTE_FALLBACK = 50.0;
    private static final double DEFAULT_DATABASE_FALLBACK = 100.0;
    private static final double DEFAULT_DISK_FALLBACK = 10.0;

    /**
     * Conservative fallback storage usage (1 GiB) used only when the
     * Azure Monitor {@code UsedCapacity} metric is unavailable.  For
     * student / free-tier accounts with near-zero usage this may still
     * overestimate – the {@code CostResolutionChain} ensures the UI
     * clearly marks it as {@code ESTIMATED}, never as authoritative billing.
     */
    private static final double FALLBACK_STORAGE_GB = 1.0;

    private final PricingConfig config;
    private final StorageUsageProvider storageUsageProvider;

    public PricingEngine(PricingConfig config, StorageUsageProvider storageUsageProvider) {
        this.config = config;
        this.storageUsageProvider = storageUsageProvider;
    }

    /**
     * Estimates monthly cost for an Azure resource based on type and SKU.
     *
     * @param resource Azure resource with SKU and region metadata
     * @return Monthly cost in USD
     */
    public double estimateMonthlyCost(AzureResource resource) {
        double baseCost = estimateBaseCost(resource);
        double regionalCost = applyRegionalMultiplier(baseCost, resource.region());
        log.debug("Cost estimation for '{}': base=${}, regional=${}",
                resource.name(), baseCost, regionalCost);
        return regionalCost;
    }

    /**
     * Calculates potential monthly savings by comparing SKU tiers.
     */
    public double calculateMonthlySavings(AzureResource resource) {
        if (resource.sku() == null || resource.sku().equalsIgnoreCase("N/A")) {
            return 0.0;
        }

        String sku = resource.sku().toUpperCase();
        double currentCost = estimateMonthlyCost(resource);
        double optimizedCost = switch (resource.type().toLowerCase()) {
            case "microsoft.storage/storageaccounts" -> estimateOptimizedStorageCost(resource, sku);
            case "microsoft.compute/virtualmachines" -> estimateOptimizedVmCost(resource, sku);
            case "microsoft.dbforpostgresql/servers" -> estimateOptimizedDatabaseCost(resource, sku);
            case "microsoft.sql/servers/databases" -> estimateOptimizedSqlCost(resource, sku);
            case "microsoft.network/publicipaddresses" -> 0.0;
            case "microsoft.network/loadbalancers" -> 0.0;
            case "microsoft.compute/disks" -> 0.0;
            default -> currentCost;
        };

        return Math.max(0.0, currentCost - optimizedCost);
    }

    // ── Optimized cost estimates ────────────────────────────────────────────

    private double estimateOptimizedStorageCost(AzureResource resource, String sku) {
        double baseCost = estimateMonthlyCost(resource);
        if (sku.contains("GRS")) {
            return baseCost * 0.67;
        }
        return baseCost;
    }

    private double estimateOptimizedVmCost(AzureResource resource, String sku) {
        double baseCost = estimateMonthlyCost(resource);
        if (sku.contains("_D4") || sku.contains("_D8")) {
            return baseCost * 0.5;
        } else if (sku.contains("_D2")) {
            return baseCost * 0.75;
        }
        return baseCost;
    }

    private double estimateOptimizedDatabaseCost(AzureResource resource, String sku) {
        double baseCost = estimateMonthlyCost(resource);
        if (sku.contains("GP_") || sku.contains("STANDARD")) {
            return baseCost * 0.7;
        }
        return baseCost;
    }

    private double estimateOptimizedSqlCost(AzureResource resource, String sku) {
        double baseCost = estimateMonthlyCost(resource);
        if (sku.contains("PREMIUM") || sku.contains("P")) {
            return baseCost * 0.3;
        } else if (sku.contains("STANDARD") || sku.contains("S")) {
            return baseCost * 0.6;
        }
        return baseCost;
    }

    // ── Base cost lookup ────────────────────────────────────────────────────

    private double estimateBaseCost(AzureResource resource) {
        String type = resource.type().toLowerCase();
        String sku = (resource.sku() != null) ? resource.sku().toUpperCase() : "N/A";

        return switch (type) {
            case "microsoft.storage/storageaccounts" -> estimateStorageAccountCost(resource, sku);
            case "microsoft.compute/virtualmachines" -> estimateVmCost(sku);
            case "microsoft.dbforpostgresql/servers" -> estimatePostgresqlCost(sku);
            case "microsoft.sql/servers/databases" -> estimateSqlDatabaseCost(sku);
            case "microsoft.network/publicipaddresses" -> estimatePublicIpCost(sku);
            case "microsoft.network/networkinterfaces" -> 0.32;
            case "microsoft.network/loadbalancers" -> 16.0;
            case "microsoft.compute/disks" -> estimateDiskCost(sku);
            default -> safeConfigDouble("defaultUnknownCost", DEFAULT_UNKNOWN_COST);
        };
    }

    private double estimateStorageAccountCost(AzureResource resource, String sku) {
        Map<String, Double> rates = effectiveRates(config.storageRates(), DEFAULT_STORAGE_RATES);
        double ratePerGb = matchSkuRate(sku, rates, 0.023);
        if (ratePerGb <= 0.0) return 0.0;  // unknown SKU → do not invent costs

        double usedGb = storageUsageProvider.getUsedGb(resource.id());
        if (Double.isNaN(usedGb) || usedGb <= 0.0) {
            usedGb = FALLBACK_STORAGE_GB;
            log.debug("[Pricing] Storage '{}' ({}) – UsedCapacity unavailable, using fallback {} GiB",
                    resource.name(), sku, usedGb);
        } else {
            log.debug("[Pricing] Storage '{}' ({}) – using real UsedCapacity: {} GiB",
                    resource.name(), sku, usedGb);
        }

        return ratePerGb * usedGb;
    }

    private double estimateVmCost(String sku) {
        Map<String, Double> rates = effectiveRates(config.computeRates(), DEFAULT_COMPUTE_RATES);
        return matchSkuRate(sku, rates, DEFAULT_COMPUTE_FALLBACK);
    }

    private double estimatePostgresqlCost(String sku) {
        Map<String, Double> rates = effectiveRates(config.databaseRates(), DEFAULT_DATABASE_RATES);
        return matchSkuRate(sku, rates, DEFAULT_DATABASE_FALLBACK);
    }

    private double estimateSqlDatabaseCost(String sku) {
        // Use database rates for SQL too (Basic/Standard/Premium pattern)
        if (sku.contains("BASIC") || sku.contains("B")) return 5.0;
        if (sku.contains("STANDARD") || sku.contains("S")) return 30.0;
        if (sku.contains("PREMIUM") || sku.contains("P")) return 400.0;
        return 50.0;
    }

    private double estimatePublicIpCost(String sku) {
        Map<String, Double> rates = effectiveRates(config.networkRates(), DEFAULT_NETWORK_RATES);
        if ("Dynamic".equalsIgnoreCase(sku)) {
            return rates.getOrDefault("dynamic", 0.0);
        }
        return rates.getOrDefault("static", 3.0);
    }

    private double estimateDiskCost(String sku) {
        Map<String, Double> rates = effectiveRates(config.diskRates(), DEFAULT_DISK_RATES);
        return matchSkuRate(sku, rates, DEFAULT_DISK_FALLBACK);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Returns the config map if non-null/non-empty, otherwise the default. */
    private static Map<String, Double> effectiveRates(Map<String, Double> configRates,
                                                       Map<String, Double> defaultRates) {
        return (configRates != null && !configRates.isEmpty()) ? configRates : defaultRates;
    }

    /** Finds the first key contained in {@code sku} and returns its rate. */
    private static double matchSkuRate(String sku, Map<String, Double> rates, double fallback) {
        for (var entry : rates.entrySet()) {
            if (sku.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return fallback;
    }

    private double safeConfigDouble(String field, double fallback) {
        if (config == null) return fallback;
        return switch (field) {
            case "defaultUnknownCost" ->
                    Double.isNaN(config.defaultUnknownCost()) ? fallback : config.defaultUnknownCost();
            default -> fallback;
        };
    }

    private double applyRegionalMultiplier(double baseCost, String region) {
        if (region == null || region.isBlank()) {
            return baseCost;
        }
        Map<String, Double> multipliers = effectiveRates(
                config.regionMultipliers(), DEFAULT_REGION_MULTIPLIERS);

        String normalizedRegion = region.toLowerCase()
                .replaceAll("\\s+", "")
                .replaceAll("[^a-z0-9]", "");

        return baseCost * multipliers.getOrDefault(normalizedRegion, 1.0);
    }
}
