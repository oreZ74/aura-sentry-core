package de.orez.aura_sentry_core.model;

/**
 * Enumerates every possible source of cost data in the system.
 * Each {@link CostProfile} is tagged with exactly one source,
 * making provenance transparent throughout the entire pipeline.
 */
public enum CostSource {

    /** Authoritative month-to-date billing from Azure Cost Management API. */
    ACTUAL_BILLING,

    /**
     * Amortized cost from Azure Cost Management API
     * (spreads reservation / savings-plan charges across the billing period).
     */
    AMORTIZED,

    /** Heuristic estimation from the local {@code PricingEngine}. */
    ESTIMATED,

    /**
     * No cost data could be obtained.  The API is unreachable,
     * credentials are missing, or the resource has no billing record yet.
     */
    UNAVAILABLE
}
