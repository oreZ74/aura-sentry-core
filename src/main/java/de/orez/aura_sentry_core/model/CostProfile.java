package de.orez.aura_sentry_core.model;

/**
 * Sealed interface that strictly separates where cost information
 * originated from.  Every {@link OptimizationResult} carries exactly
 * one {@code CostProfile} instance, never raw doubles.
 *
 * <h3>Permitted variants</h3>
 * <ul>
 *   <li>{@link ActualCost} – from Azure Cost Management API (authoritative)</li>
 *   <li>{@link EstimatedCost} – from local {@code PricingEngine} (heuristic)</li>
 *   <li>{@link UnavailableCost} – API unreachable, resource too new, etc.</li>
 * </ul>
 *
 * <p><b>Crucial invariant:</b> {@code ActualCost(0.0, …)} is valid
 * (Free-Tier / idle / Azure for Students).  It must never be
 * overwritten by an {@code EstimatedCost}.
 */
public sealed interface CostProfile
        permits CostProfile.ActualCost,
        CostProfile.EstimatedCost,
        CostProfile.UnavailableCost {

    double amount();

    String currencySymbol();

    CostSource source();

    /** Month-to-date cost returned by the Azure Cost Management API. */
    record ActualCost(double amount, String currency) implements CostProfile {
        @Override
        public String currencySymbol() {
            return BillingInfo.currencySymbol(currency);
        }

        @Override
        public CostSource source() {
            return CostSource.ACTUAL_BILLING;
        }
    }

    /** Heuristic estimate computed by the local {@code PricingEngine}. */
    record EstimatedCost(double amount, String method, PricingContext context) implements CostProfile {
        @Override
        public String currencySymbol() {
            return "€";
        }

        @Override
        public CostSource source() {
            return CostSource.ESTIMATED;
        }
    }

    /**
     * Sentinel value indicating that no cost data could be obtained.
     * Used when the API is unreachable, credentials are missing,
     * or the resource was created too recently to appear in billing.
     */
    record UnavailableCost(String reason) implements CostProfile {
        @Override
        public double amount() {
            return 0.0;
        }

        @Override
        public String currencySymbol() {
            return "€";
        }

        @Override
        public CostSource source() {
            return CostSource.UNAVAILABLE;
        }
    }

    record PricingContext(String resourceType, String sku, String region) {}

    /**
     * Value of exactly {@code 0.0} from the Cost Management API is
     * authoritative – it represents a Free-Tier or idle resource.
     * Helper to make this intent explicit in calling code.
     */
    default boolean isZeroActualCost() {
        return this instanceof ActualCost && amount() == 0.0;
    }
}
