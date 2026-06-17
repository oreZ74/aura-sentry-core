package de.orez.aura_sentry_core.controller;

import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.orez.aura_sentry_core.advisor.RemediationAgent;
import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto;
import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.controller.dto.ResourceCacheDto;
import de.orez.aura_sentry_core.model.AzureResource;
import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.OptimizationResult;
import de.orez.aura_sentry_core.optimization.OptimizationEngine;
import de.orez.aura_sentry_core.persistence.ScanPersistenceService;
import de.orez.aura_sentry_core.persistence.entity.ResourceCacheEntity;
import de.orez.aura_sentry_core.persistence.repository.CloudCredentialsRepository;
import de.orez.aura_sentry_core.persistence.repository.ResourceCacheRepository;
import de.orez.aura_sentry_core.persistence.repository.UserRepository;
import de.orez.aura_sentry_core.service.CloudProviderService;
import de.orez.aura_sentry_core.service.ResourceCacheService;
import de.orez.aura_sentry_core.service.demo.DemoModeProvider;

@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final RemediationAgent remediationAgent;
    private final ScanPersistenceService scanPersistenceService;
    private final ResourceCacheService resourceCacheService;
    private final ResourceCacheRepository resourceCacheRepository;
    private final CloudCredentialsRepository credentialsRepository;
    private final UserRepository userRepository;
    private final CloudProviderService cloudProviderService;
    private final OptimizationEngine optimizationEngine;
    private final OptimizationResultMapper mapper;
    private final DemoModeProvider demoModeProvider;

    public DashboardService(RemediationAgent remediationAgent,
                            ScanPersistenceService scanPersistenceService,
                            ResourceCacheService resourceCacheService,
                            ResourceCacheRepository resourceCacheRepository,
                            CloudCredentialsRepository credentialsRepository,
                            UserRepository userRepository,
                            CloudProviderService cloudProviderService,
                            OptimizationEngine optimizationEngine,
                            OptimizationResultMapper mapper,
                            DemoModeProvider demoModeProvider) {
        this.remediationAgent = remediationAgent;
        this.scanPersistenceService = scanPersistenceService;
        this.resourceCacheService = resourceCacheService;
        this.resourceCacheRepository = resourceCacheRepository;
        this.credentialsRepository = credentialsRepository;
        this.userRepository = userRepository;
        this.cloudProviderService = cloudProviderService;
        this.optimizationEngine = optimizationEngine;
        this.mapper = mapper;
        this.demoModeProvider = demoModeProvider;
    }

    public record DashboardData(
            List<ResourceCacheDto> cached,
            long warningCount,
            String totalMonthlyCost,
            String totalMonthlySavings,
            String totalAnnualSavings,
            String currencySymbol,
            boolean credentialsMissing,
            boolean azureError,
            String azureErrorMessage) {

        public static DashboardData noCredentials() {
            return new DashboardData(
                    Collections.emptyList(), 0,
                    "€0.00", "€0.00", "€0.00", "€",
                    true, false, null);
        }

        public static DashboardData withError(String message) {
            return new DashboardData(
                    Collections.emptyList(), 0,
                    "€0.00", "€0.00", "€0.00", "€",
                    false, true, message);
        }

        public static DashboardData fromCache(List<ResourceCacheDto> cached) {
            long warningCount = cached.stream()
                    .filter(r -> r.flag() != IssueType.HEALTHY && r.flag() != IssueType.NEW_RESOURCE)
                    .count();

            double totalMonthlyCost = cached.stream()
                    .mapToDouble(ResourceCacheDto::cost)
                    .sum();

            double totalMonthlySavings = cached.stream()
                    .filter(r -> r.flag() != IssueType.HEALTHY
                            && r.flag() != IssueType.ORPHANED
                            && r.flag() != IssueType.NEW_RESOURCE)
                    .mapToDouble(ResourceCacheDto::cost)
                    .sum();

            String currencySymbol = cached.isEmpty() ? "$" : cached.getFirst().currency();

            return new DashboardData(
                    cached,
                    warningCount,
                    "%s%.2f".formatted(currencySymbol, totalMonthlyCost),
                    "%s%.2f".formatted(currencySymbol, totalMonthlySavings),
                    "%s%.2f".formatted(currencySymbol, totalMonthlySavings * 12),
                    currencySymbol,
                    false, false, null);
        }
    }

    public DashboardData loadDashboard(boolean demoMode) {
        if (!demoMode) {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            String currentUser = (auth != null) ? auth.getName() : null;
            if (currentUser != null) {
                var user = userRepository.findByUsername(currentUser).orElse(null);
                if (user != null) {
                    boolean hasCredentials = credentialsRepository
                            .findTopByUserOrderByUpdatedAtDesc(user).isPresent();
                    if (!hasCredentials) {
                        log.info("[Dashboard] No credentials found for user – skipping Azure scan");
                        return DashboardData.noCredentials();
                    }
                }
            }
        }

        List<ResourceCacheDto> cached = resourceCacheService.getAllResources();
        log.info("[Dashboard] Loaded {} resources from cache", cached.size());
        return DashboardData.fromCache(cached);
    }

    public AnalysisReportDto getInsight(String resourceId, boolean demoMode) {
        if (demoModeProvider.isDemoMode()) {
            return handleDemoInsight(resourceId);
        }
        return handleLiveInsight(resourceId, demoMode);
    }

    private AnalysisReportDto handleDemoInsight(String resourceId) {
        var match = cloudProviderService.fetchResources().stream()
                .filter(r -> r.id().equalsIgnoreCase(resourceId))
                .findFirst()
                .orElse(null);

        if (match == null) {
            log.warn("[AI Insight DEMO] Resource '{}' not found in demo data", resourceId);
            return AnalysisReportDto.fallback(
                    "Demo resource '" + resourceId + "' not found. Try the Re-Scan button.");
        }

        List<OptimizationResult> analyzed = optimizationEngine.analyze(List.of(match));
        OptimizationResult result = analyzed.isEmpty()
                ? mapper.mapFromDemoResource(match)
                : analyzed.get(0);

        AnalysisReportDto llmReport = remediationAgent.advise(result);

        IssueType engineFlag = result.findings().stream()
                .filter(f -> f.flag() != IssueType.HEALTHY)
                .findFirst()
                .map(OptimizationFinding::flag)
                .orElse(IssueType.HEALTHY);

        return new AnalysisReportDto(
                llmReport.summary(),
                engineFlag != IssueType.HEALTHY,
                engineFlag,
                llmReport.savingsPotentialUsd(),
                llmReport.severity(),
                llmReport.remediationScripts(),
                llmReport.recommendations());
    }

    private AnalysisReportDto handleLiveInsight(String resourceId, boolean demoMode) {
        var entity = resourceCacheRepository.findByAzureIdIgnoreCase(resourceId).orElse(null);

        if (entity == null) {
            log.warn("[AI Insight] Resource '{}' not found in cache", resourceId);
            return AnalysisReportDto.fallback("Resource not found in cache. Run a Re-Scan first.");
        }

        OptimizationResult result = mapper.mapFromCache(entity);
        AnalysisReportDto llmReport = remediationAgent.advise(result);

        IssueType dbFlag = entity.getFlag();
        boolean issueDetected = dbFlag != IssueType.HEALTHY;
        AnalysisReportDto report = new AnalysisReportDto(
                llmReport.summary(),
                issueDetected,
                dbFlag,
                llmReport.savingsPotentialUsd(),
                llmReport.severity(),
                llmReport.remediationScripts(),
                llmReport.recommendations());

        if (!demoMode) {
            scanPersistenceService.updateFindingWithAiReport(
                    resourceId,
                    java.util.Objects.toString(report.issueType(), "UNKNOWN"),
                    report.summary());
        }

        return report;
    }
}
