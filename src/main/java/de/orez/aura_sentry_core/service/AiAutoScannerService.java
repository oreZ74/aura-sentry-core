package de.orez.aura_sentry_core.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import de.orez.aura_sentry_core.advisor.RemediationAgent;
import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto;
import de.orez.aura_sentry_core.model.OptimizationResult;
import de.orez.aura_sentry_core.persistence.ScanPersistenceService;
import de.orez.aura_sentry_core.persistence.repository.ScanHistoryRepository;

/**
 * Background AI auto-analyser service.
 *
 * <p>After the initial dashboard scan completes, this service is triggered
 * asynchronously to run Gemini analysis on all resources that have not been
 * AI-locked yet. This populates the database with AI findings so that
 * future UI notifications (bell icon) can display them without requiring
 * the user to manually click "Analyse" on each resource.
 *
 * <p>Runs on a Spring {@code @Async} thread pool – never blocks the
 * request thread that renders the dashboard.
 */
@Service
public class AiAutoScannerService {

    private static final Logger log = LoggerFactory.getLogger(AiAutoScannerService.class);

    /** Delay between Gemini API calls to avoid rate-limiting (ms). */
    private static final long API_THROTTLE_MS = 1_000;

    private final ScanHistoryRepository scanHistoryRepository;
    private final ScanPersistenceService scanPersistenceService;
    private final RemediationAgent remediationAgent;

    public AiAutoScannerService(
            ScanHistoryRepository scanHistoryRepository,
            ScanPersistenceService scanPersistenceService,
            RemediationAgent remediationAgent) {
        this.scanHistoryRepository = scanHistoryRepository;
        this.scanPersistenceService = scanPersistenceService;
        this.remediationAgent = remediationAgent;
    }

    /**
     * Analyses all unlocked (non-AI-analysed) resources in the background.
     *
     * <p>For each resource that has a persisted scan result but no AI lock,
     * calls the {@link RemediationAgent} and persists the result via
     * {@link ScanPersistenceService#updateFindingWithAiReport}. A 1-second
     * delay between calls prevents Gemini API rate-limit errors.
     *
     * <p>The method propagates the caller's {@link SecurityContextHolder}
     * identity so that the {@link RemediationAgent} can resolve the correct
     * Gemini API key from the user's encrypted credentials.
     *
     * @param username the authenticated user's username (for logging)
     * @param results  the live scan results from the OptimizationEngine
     */
    @Async
    public void analyzeUnlockedResourcesInBackground(String username, List<OptimizationResult> results) {
        log.info("[AutoScanner] Starting background AI analysis for user '{}' ({} live results)",
                username, results.size());

        List<String> unlockedIds;
        try {
            unlockedIds = scanHistoryRepository.findUnlockedResourceIds();
        } catch (Exception e) {
            log.error("[AutoScanner] Failed to query unlocked resource IDs: {}", e.getMessage(), e);
            return;
        }

        if (unlockedIds.isEmpty()) {
            log.info("[AutoScanner] No unlocked resources to analyse for user '{}'", username);
            return;
        }

        log.info("[AutoScanner] Found {} unlocked resources to analyse", unlockedIds.size());

        int analysed = 0;
        int failed = 0;

        for (String resourceId : unlockedIds) {
            // Find the matching live result for this resource
            OptimizationResult matchingResult = results.stream()
                    .filter(r -> r.resourceId().equals(resourceId))
                    .findFirst()
                    .orElse(null);

            if (matchingResult == null) {
                log.debug("[AutoScanner] Skipping '{}' – no matching live result", resourceId);
                continue;
            }

            try {
                AnalysisReportDto report = remediationAgent.advise(matchingResult);

                scanPersistenceService.updateFindingWithAiReport(
                        resourceId,
                        java.util.Objects.toString(report.issueType(), "UNKNOWN"),
                        report.summary(), username);

                analysed++;
                log.info("[AutoScanner] [{}/{}] Analysed '{}' → {} (savings: ${})",
                        analysed, unlockedIds.size(),
                        resourceId, report.issueType(), report.savingsPotentialUsd());

            } catch (Exception e) {
                failed++;
                log.warn("[AutoScanner] Failed to analyse '{}': {} – skipping",
                        resourceId, e.getMessage());
            }

            // Throttle to avoid Gemini rate limits
            try {
                Thread.sleep(API_THROTTLE_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[AutoScanner] Interrupted – aborting background analysis");
                return;
            }
        }

        log.info("[AutoScanner] Completed background analysis for user '{}': {} analysed, {} failed",
                username, analysed, failed);
    }
}
