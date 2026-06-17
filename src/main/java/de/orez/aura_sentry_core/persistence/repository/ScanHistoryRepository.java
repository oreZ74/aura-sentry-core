package de.orez.aura_sentry_core.persistence.repository;

import java.util.List;
import java.util.UUID;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import de.orez.aura_sentry_core.persistence.entity.ScanResultEntity;

public interface ScanHistoryRepository extends JpaRepository<ScanResultEntity, UUID> {

    List<ScanResultEntity> findTop10ByOrderByScannedAtDesc();

    ScanResultEntity findTopByResourceIdOrderByScannedAtDesc(String resourceId);

    /**
     * Returns distinct resource IDs that have NOT been AI-analysed yet
     * (aiLocked = false), picking only the latest scan per resource.
     */
    @Query("""
            SELECT s.resourceId FROM ScanResultEntity s
            WHERE s.scannedAt = (
                SELECT MAX(s2.scannedAt) FROM ScanResultEntity s2
                WHERE s2.resourceId = s.resourceId
            )
            AND s.aiLocked = false
            """)
    List<String> findUnlockedResourceIds();
}
