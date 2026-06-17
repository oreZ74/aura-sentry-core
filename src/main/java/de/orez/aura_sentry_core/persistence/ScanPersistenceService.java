package de.orez.aura_sentry_core.persistence;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;

import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.OptimizationResult;
import de.orez.aura_sentry_core.model.ResourceState;
import de.orez.aura_sentry_core.persistence.entity.ScanFindingEntity;
import de.orez.aura_sentry_core.persistence.entity.ScanResultEntity;
import de.orez.aura_sentry_core.persistence.mapper.ScanResultMapper;
import de.orez.aura_sentry_core.persistence.repository.ScanHistoryRepository;
import de.orez.aura_sentry_core.service.NotificationService;

@Service
public class ScanPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ScanPersistenceService.class);

    private final ScanHistoryRepository repository;
    private final NotificationService notificationService;

    public ScanPersistenceService(ScanHistoryRepository repository,
                                  NotificationService notificationService) {
        this.repository = repository;
        this.notificationService = notificationService;
    }

    /**
     * Persists scan results asynchronously using virtual threads.
     * The calling request thread is not blocked.
     */
    public void persistAsync(List<OptimizationResult> results, Instant scannedAt) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> persistAll(results, scannedAt));
        }
    }

    @Transactional
    protected void persistAll(List<OptimizationResult> results, Instant scannedAt) {
        try {
            var entities = results.stream()
                    .map(r -> {
                        var entity = ScanResultMapper.toEntity(r, scannedAt);
                        var locked = repository.findTopByResourceIdOrderByScannedAtDesc(r.resourceId());
                        if (locked != null && locked.isAiLocked()) {
                            entity.setAiLocked(true);
                            entity.getFindings().clear();
                            locked.getFindings().forEach(f ->
                                    entity.addFinding(new ScanFindingEntity(f.getFlag(), f.getReason())));
                            log.info("[Persistence] AI-locked: preserved finding for '{}' (static: {} → AI: {})",
                                    r.resourceId(),
                                    r.findings().isEmpty() ? "HEALTHY" : r.findings().get(0).flag(),
                                    entity.getFindings().isEmpty() ? "HEALTHY" : entity.getFindings().get(0).getFlag());
                        }
                        return entity;
                    })
                    .toList();
            repository.saveAll(entities);
            log.info("Persisted {} scan results for scan at {}", entities.size(), scannedAt);
        } catch (Exception e) {
            log.error("Failed to persist scan results for scan at {}", scannedAt, e);
        }
    }

    /**
     * Merges AI-locked findings from the database into the in-memory live results.
     *
     * <p>This method runs inside a {@code @Transactional} boundary so that
     * Hibernate's lazy-loaded {@code findings} collection can be safely
     * accessed. The collection is force-initialized via {@link Hibernate#initialize}
     * before any field access, which prevents {@code LazyInitializationException}.
     *
     * @param liveResults the freshly computed optimization results from the engine
     * @return the updated list with AI-overridden issue types where applicable
     */
    @Transactional(readOnly = true)
    public List<OptimizationResult> applyAiOverrides(List<OptimizationResult> liveResults) {
        return liveResults.stream().map(r -> {
            var locked = repository.findTopByResourceIdOrderByScannedAtDesc(r.resourceId());
            if (locked != null && locked.isAiLocked()) {
                // Force-initialize the lazy proxy while the session is still open
                Hibernate.initialize(locked.getFindings());
                if (!locked.getFindings().isEmpty()) {
                    var aiFinding = locked.getFindings().get(0);
                    log.debug("[AI Override] Overriding result for '{}': {} → {}",
                            r.resourceId(),
                            r.findings().isEmpty() ? "HEALTHY" : r.findings().get(0).flag(),
                            aiFinding.getFlag());
                    return new OptimizationResult(
                            r.resourceId(), r.resourceName(), r.resourceType(), r.resourceGroup(),
                            r.state(),
                            List.of(new OptimizationFinding(aiFinding.getFlag(), aiFinding.getReason())),
                            r.estimatedMonthlyCost(), r.estimatedMonthlySavings(),
                            r.currencySymbol(), r.costSource(), r.sku(), r.region(), r.tags());
                }
            }
            return r;
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<OptimizationResult> getRecentScans() {
        return repository.findTop10ByOrderByScannedAtDesc().stream()
                .map(ScanResultMapper::toRecord)
                .toList();
    }

    /**
     * Updates the AI-locked finding for a resource and persists the result.
     * Uses "anonymous" as the username context (called from background auto-scanner).
     *
     * @param resourceId   the Azure resource ID
     * @param aiIssueType  the new issue type string from the AI report
     * @param aiSummary    the AI-generated summary
     */
    @Transactional
    public void updateFindingWithAiReport(String resourceId, String aiIssueType, String aiSummary) {
        updateFindingWithAiReport(resourceId, aiIssueType, aiSummary, null);
    }

    /**
     * Updates the AI-locked finding for a resource, detecting status changes
     * and creating a notification for the given user when the status changed.
     *
     * @param resourceId   the Azure resource ID
     * @param aiIssueType  the new issue type string from the AI report
     * @param aiSummary    the AI-generated summary
     * @param username     the authenticated user's username for notifications
     *                     (may be {@code null} – notification is skipped in that case)
     */
    @Transactional
    public void updateFindingWithAiReport(String resourceId, String aiIssueType,
                                          String aiSummary, String username) {
        IssueType newIssueType = IssueType.fromString(aiIssueType);
        var existing = repository.findTopByResourceIdOrderByScannedAtDesc(resourceId);
        ScanResultEntity entity;

        if (existing != null) {
            // ── Change Detection ─────────────────────────────────────────────
            if (!existing.getFindings().isEmpty()) {
                IssueType oldIssueType = existing.getFindings().get(0).getFlag();
                log.info("[Persistence] Updating AI finding for '{}': {} → {}",
                        resourceId, oldIssueType, newIssueType);

                // Notify user only on a real status change
                if (oldIssueType != newIssueType && username != null && !username.isBlank()) {
                    String resourceName = existing.getResourceName() != null
                            ? existing.getResourceName() : resourceId;
                    notificationService.createNotification(
                            username, resourceName,
                            oldIssueType.name(), newIssueType.name());
                }
            }
            entity = existing;
            entity.getFindings().clear();
        } else {
            entity = new ScanResultEntity(
                    Instant.now(), resourceId, resourceId, "AI-Generated", "AI-Generated",
                    ResourceState.RUNNING);
            log.info("[Persistence] Creating new AI-locked scan entry for '{}' → {}", resourceId, newIssueType);
        }

        entity.addFinding(new ScanFindingEntity(newIssueType, aiSummary));
        entity.setAiLocked(true);
        repository.save(entity);
    }
}
