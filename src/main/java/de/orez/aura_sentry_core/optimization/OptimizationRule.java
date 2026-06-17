package de.orez.aura_sentry_core.optimization;

import java.util.Optional;

import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.ResourceCategory;

/**
 * Functional interface for a single optimization rule.
 * New rules can be added simply by implementing this interface.
 */
@FunctionalInterface
public interface OptimizationRule {

    Optional<OptimizationFinding> evaluate(ResourceCategory category);
}
