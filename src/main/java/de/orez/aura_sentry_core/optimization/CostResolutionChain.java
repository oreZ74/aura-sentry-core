package de.orez.aura_sentry_core.optimization;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import de.orez.aura_sentry_core.model.AzureResource;
import de.orez.aura_sentry_core.model.BillingInfo;
import de.orez.aura_sentry_core.model.CostApiResult;
import de.orez.aura_sentry_core.model.CostProfile;
import de.orez.aura_sentry_core.model.CostProfile.ActualCost;
import de.orez.aura_sentry_core.model.CostProfile.EstimatedCost;
import de.orez.aura_sentry_core.model.CostProfile.PricingContext;
import de.orez.aura_sentry_core.model.CostProfile.UnavailableCost;
import de.orez.aura_sentry_core.service.PricingEngine;

/**
 * Chain-of-Responsibility resolver that determines the authoritative
 * {@link CostProfile} for a resource by trying multiple strategies in
 * priority order.
 *
 * <h3>Resolution priority</h3>
 * <ol>
 * <li><b>Actual Billing</b> – Azure Cost Management API.
 * A cost of {@code 0.0} is authoritative and never overwritten.</li>
 * <li><b>Pricing Engine</b> – local heuristic estimation.
 * Only used when the API <em>succeeded</em> but had no entry for
 * this specific resource. <b>Never</b> used when the API itself
 * failed (rate limit, auth error) – otherwise estimates are
 * presented as real billing data.</li>
 * <li><b>Unavailable</b> – terminal sentinel when all prior
 * strategies fail (e.g. engine returns 0.0 for unknown SKU).</li>
 * </ol>
 *
 * <p>
 * Each strategy returns {@link Optional} – the chain picks the
 * first non-empty result.
 */
@Component
public class CostResolutionChain {

    private static final Logger log = LoggerFactory.getLogger(CostResolutionChain.class);

    private final PricingEngine pricingEngine;

    public CostResolutionChain(PricingEngine pricingEngine) {
        this.pricingEngine = pricingEngine;
    }

    /**
     * Resolves the best available cost profile for a resource.
     */
    public CostProfile resolve(AzureResource resource, CostApiResult costApiResult) {
        String lowerId = resource.id().toLowerCase().trim();
        Map<String, BillingInfo> actualCosts = costApiResult.costsByResource();

        return resolveFromActual(lowerId, actualCosts)
                .or(() -> resolveFromPricingEngine(resource, lowerId, costApiResult))
                .or(() -> resolveUnavailable(resource, lowerId, costApiResult))
                .orElseThrow();
    }

    // ── Priority 1: Azure Cost Management API ──────────────────────────────

    private Optional<CostProfile> resolveFromActual(String lowerId,
            Map<String, BillingInfo> actualCosts) {
        BillingInfo billingInfo = actualCosts.get(lowerId);
        if (billingInfo == null) {
            return Optional.empty();
        }

        CostProfile profile = new ActualCost(billingInfo.amount(), billingInfo.currency());
        log.debug("[CostResolution] '{}' → {} {} (API)",
                lowerId, profile.currencySymbol(), profile.amount());
        return Optional.of(profile);
    }

    // ── Priority 2: local PricingEngine heuristic ───────────────────────────

    private Optional<CostProfile> resolveFromPricingEngine(AzureResource resource,
            String lowerId,
            CostApiResult costApiResult) {
        if (!costApiResult.apiSucceeded()) {
            log.info("[CostResolution] '{}' – API call failed → skipping PricingEngine to avoid phantom costs",
                    resource.name());
            return Optional.empty();
        }

        double estimate = pricingEngine.estimateMonthlyCost(resource);

        if (estimate <= 0.0) {
            log.debug("[CostResolution] '{}' → PricingEngine returned 0.0 (unknown SKU / type)", lowerId);
            return Optional.empty();
        }

        PricingContext ctx = new PricingContext(
                resource.type(),
                resource.sku(),
                resource.region());

        log.info("[CostResolution] '{}' not in billing ({} entries) → EstimatedCost: €{}",
                resource.name(), costApiResult.costsByResource().size(), estimate);

        return Optional.of(new EstimatedCost(estimate, "PricingEngine::estimateMonthlyCost", ctx));
    }

    // ── Priority 3: terminal sentinel ───────────────────────────────────────

    private Optional<CostProfile> resolveUnavailable(AzureResource resource,
            String lowerId,
            CostApiResult costApiResult) {
        String reason;
        if (!costApiResult.apiSucceeded()) {
            reason = "Azure Cost Management API returned HTTP 429 (rate limit) or other error – no cost data available";
        } else if (costApiResult.costsByResource().isEmpty()) {
            reason = "Cost Management API returned no data (possible permission issue)";
        } else if (!costApiResult.costsByResource().containsKey(lowerId)) {
            reason = "Resource not found in billing data (may be too new or not yet metered)";
        } else {
            reason = "PricingEngine could not estimate cost (unknown resource type or SKU)";
        }

        log.warn("[CostResolution] '{}' → UnavailableCost: {}", resource.name(), reason);
        return Optional.of(new UnavailableCost(reason));
    }
}
