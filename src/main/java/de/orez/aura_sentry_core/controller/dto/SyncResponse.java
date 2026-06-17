package de.orez.aura_sentry_core.controller.dto;

import java.time.Instant;

/**
 * Structured JSON response for the Azure sync endpoint.
 *
 * <p>Replaces the previous plain-text response so the frontend can
 * render a loading spinner, cooldown timer and "last synced" timestamp
 * without parsing free-form strings.
 *
 * @param upsertedCount number of resources written to the cache (updated + inserted)
 * @param message       human-readable status message
 * @param lastSyncedAt  ISO-8601 instant when the sync completed (used for the
 *                      frontend cooldown timer and "last synced" display)
 */
public record SyncResponse(int upsertedCount, String message, Instant lastSyncedAt) {
}