package de.orez.aura_sentry_core.model;

import java.util.Map;

/**
 * Typed result wrapper for {@code AzureCostManagementService} calls that
 * clearly separates &quot;API succeeded but returned no rows for this
 * resource&quot; from &quot;API failed entirely (rate limit, auth error, etc.)&quot;.
 *
 * <p>Without this distinction the {@code CostResolutionChain} cannot tell
 * whether an empty billing map means &quot;never query this resource&quot; or
 * &quot;try again later&quot; – leading to PricingEngine estimates being
 * presented as authoritative numbers when the API was simply unreachable.
 */
public record CostApiResult(
        Map<String, BillingInfo> costsByResource,
        boolean apiSucceeded) {

    public static CostApiResult success(Map<String, BillingInfo> costs) {
        return new CostApiResult(costs, true);
    }

    public static CostApiResult failure() {
        return new CostApiResult(Map.of(), false);
    }

    public boolean isEmpty() {
        return costsByResource.isEmpty();
    }
}
