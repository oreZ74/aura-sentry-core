package de.orez.aura_sentry_core.advisor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto;
import de.orez.aura_sentry_core.model.OptimizationResult;
import de.orez.aura_sentry_core.model.ResourceMetrics;
import de.orez.aura_sentry_core.persistence.entity.AiConfigEntity;
import de.orez.aura_sentry_core.persistence.repository.AiConfigRepository;
import de.orez.aura_sentry_core.service.AzureMonitorService;
import de.orez.aura_sentry_core.service.CredentialResolutionService;
import de.orez.aura_sentry_core.service.demo.DemoModeProvider;

/**
 * Orchestrates AI-powered remediation analysis.
 *
 * <p>Delegates to {@link GeminiApiClient} for HTTP transport,
 * {@link DemoReportService} for demo-mode mock reports, and
 * {@link RemediationPromptBuilder} for prompt construction.
 *
 * <p>This agent coordinates:
 * <ul>
 *   <li>Telemetry enrichment (Azure Monitor metrics)</li>
 *   <li>Demo-mode detection</li>
 *   <li>Prompt building with user-customised templates</li>
 *   <li>Parallel execution with rate-limited virtual threads</li>
 * </ul>
 */
@Service
public class RemediationAgent {

    private static final Logger log = LoggerFactory.getLogger(RemediationAgent.class);

    private final Semaphore geminiSemaphore = new Semaphore(5);

    private final GeminiApiClient geminiApiClient;
    private final DemoReportService demoReportService;
    private final CredentialResolutionService credentialResolutionService;
    private final AiConfigRepository aiConfigRepository;
    private final AzureMonitorService azureMonitorService;
    private final DemoModeProvider demoModeProvider;

    public RemediationAgent(GeminiApiClient geminiApiClient,
                            DemoReportService demoReportService,
                            CredentialResolutionService credentialResolutionService,
                            AiConfigRepository aiConfigRepository,
                            AzureMonitorService azureMonitorService,
                            DemoModeProvider demoModeProvider) {
        this.geminiApiClient = geminiApiClient;
        this.demoReportService = demoReportService;
        this.credentialResolutionService = credentialResolutionService;
        this.aiConfigRepository = aiConfigRepository;
        this.azureMonitorService = azureMonitorService;
        this.demoModeProvider = demoModeProvider;

        log.info("RemediationAgent initialized (maxConcurrentCalls=5)");
    }

    public record AzureContext(
            String lastAccessTime,
            Double averageCpuPercent,
            Long iops,
            Long throughputMbps,
            Double networkInBytes,
            Double networkOutBytes,
            Boolean isUnderutilized,
            Map<String, String> extendedProperties) {

        public static AzureContext empty() {
            return new AzureContext(null, null, null, null, null, null, null, Map.of());
        }

        public static AzureContext fromMetrics(ResourceMetrics metrics) {
            return new AzureContext(
                    null,
                    metrics.averageCpuUtilization(),
                    null,
                    null,
                    metrics.networkInBytes(),
                    metrics.networkOutBytes(),
                    metrics.isUnderutilized(),
                    Map.of());
        }
    }

    public AnalysisReportDto advise(OptimizationResult result) {
        AzureContext context = AzureContext.empty();
        try {
            ResourceMetrics metrics = azureMonitorService.fetchMetrics(result.resourceId());
            context = AzureContext.fromMetrics(metrics);
            log.info("[AI Advisor] Loaded metrics for '{}': CPU={}%, NetIn={}B, NetOut={}B, underutilized={}",
                    result.resourceName(),
                    metrics.averageCpuUtilization(),
                    metrics.networkInBytes(),
                    metrics.networkOutBytes(),
                    metrics.isUnderutilized());
        } catch (Exception e) {
            log.warn("[AI Advisor] Could not load metrics for '{}': {} – proceeding without telemetry",
                    result.resourceName(), e.getMessage());
        }
        return advise(result, context);
    }

    public AnalysisReportDto advise(OptimizationResult result, AzureContext context) {
        if (demoModeProvider.isDemoMode()) {
            log.info("[AI Advisor] Demo mode active – returning mock report for '{}'", result.resourceName());
            return demoReportService.createMockReport(result);
        }

        boolean isHealthy = result.findings().stream()
                .allMatch(f -> f.flag() == AnalysisReportDto.IssueType.HEALTHY);

        if (isHealthy) {
            log.info("Resource '{}' is healthy – skipping AI call", result.resourceName());
            return AnalysisReportDto.healthy(
                    "This resource is optimally configured. No improvement suggestions at this time.");
        }

        log.info("Requesting AI analysis for resource '{}'", result.resourceName());

        String fullPrompt = buildFullPrompt(result, context);

        try {
            return geminiApiClient.callGemini(fullPrompt);
        } catch (IllegalStateException e) {
            log.warn("[AI Advisor] Credential error for '{}': {}", result.resourceName(), e.getMessage());
            return AnalysisReportDto.fallback("Credential error: " + e.getMessage());
        } catch (RestClientException e) {
            log.error("Gemini API call failed for '{}': {}", result.resourceName(), e.getMessage());
            return AnalysisReportDto.fallback("API error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to parse Gemini response for '{}': {}", result.resourceName(), e.getMessage(), e);
            return AnalysisReportDto.fallback("Parse error: " + e.getMessage());
        }
    }

    public List<AnalysisReportDto> adviseAll(List<OptimizationResult> results) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<AnalysisReportDto>> futures = results.stream()
                    .map(result -> CompletableFuture.supplyAsync(() -> {
                        try {
                            geminiSemaphore.acquire();
                            return advise(result);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return AnalysisReportDto.fallback("Thread interrupted during rate-limiting wait");
                        } finally {
                            geminiSemaphore.release();
                        }
                    }, executor))
                    .toList();

            return futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        }
    }

    private String buildFullPrompt(OptimizationResult result, AzureContext context) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = RemediationPromptBuilder.buildUserPrompt(result, context);
        String specificInstructions = loadSpecificInstructions();

        String fullPrompt = systemPrompt + "\n\n" + userPrompt;

        if (specificInstructions != null && !specificInstructions.isBlank()) {
            fullPrompt += "\n\n## Additional Instructions\n\n" + specificInstructions;
        }

        return fullPrompt;
    }

    private String buildSystemPrompt() {
        try {
            var user = credentialResolutionService.resolveCurrentUser();
            return aiConfigRepository.findByUserUsername(user.getUsername())
                    .map(AiConfigEntity::getSystemTemplate)
                    .filter(t -> t != null && !t.isBlank())
                    .orElse(RemediationPromptBuilder.SYSTEM_PROMPT);
        } catch (IllegalStateException e) {
            log.debug("[AI Advisor] No authenticated user – using default system prompt");
            return RemediationPromptBuilder.SYSTEM_PROMPT;
        }
    }

    private String loadSpecificInstructions() {
        try {
            var user = credentialResolutionService.resolveCurrentUser();
            return aiConfigRepository.findByUserUsername(user.getUsername())
                    .map(AiConfigEntity::getSpecificInstructions)
                    .filter(s -> s != null && !s.isBlank())
                    .orElse(null);
        } catch (IllegalStateException e) {
            log.debug("[AI Advisor] No authenticated user – skipping specific instructions");
            return null;
        }
    }
}
