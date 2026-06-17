package de.orez.aura_sentry_core.config;

import java.io.Serializable;

/**
 * Lightweight session-safe representation of an authenticated account for
 * the multi-account switcher dropdown.
 */
public record ActiveAccount(String fullName, String email, String initials) implements Serializable {}
