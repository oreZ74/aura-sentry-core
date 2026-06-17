package de.orez.aura_sentry_core.persistence.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import de.orez.aura_sentry_core.persistence.entity.ResourceCacheEntity;

/**
 * Repository for the {@link ResourceCacheEntity} current-state cache.
 */
public interface ResourceCacheRepository extends JpaRepository<ResourceCacheEntity, Long> {

    /**
     * Finds a cached resource by its unique Azure resource ID.
     * Uses case-insensitive matching so that mixed-case IDs from Azure
     * (e.g. "Microsoft.Storage") are correctly matched against lowercased
     * rows in the database.
     */
    Optional<ResourceCacheEntity> findByAzureIdIgnoreCase(String azureId);

    /**
     * Returns all cached resources ordered by most recently updated first.
     */
    List<ResourceCacheEntity> findAllByOrderByUpdatedAtDesc();

    /**
     * Returns all cached resources ordered by most recently scanned first.
     */
    List<ResourceCacheEntity> findAllByOrderByScannedAtDesc();
}