package de.orez.aura_sentry_core.config;

import de.orez.aura_sentry_core.persistence.entity.UserEntity;

/**
 * Injectable abstraction over the Spring Security context so that
 * services can be unit-tested without a running SecurityContext.
 */
public interface SecurityContextProvider {

    /**
     * Returns the currently authenticated user.
     *
     * @throws IllegalStateException if no user is authenticated or the user is
     *                               not found in the database
     */
    UserEntity getCurrentUser();
}
