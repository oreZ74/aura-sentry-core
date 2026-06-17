package de.orez.aura_sentry_core.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto;
import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.OptimizationResult;
import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import de.orez.aura_sentry_core.service.CredentialResolutionService;
import de.orez.aura_sentry_core.service.demo.DemoModeProvider;

@ExtendWith(MockitoExtension.class)
class RemediationAgentTest {

    @Mock
    private GeminiApiClient geminiApiClient;

    @Mock
    private DemoReportService demoReportService;

    @Mock
    private DemoModeProvider demoModeProvider;

    @Mock
    private CredentialResolutionService credentialResolutionService;

    private RemediationAgent agent;

    private static OptimizationResult unhealthyResult() {
        return new OptimizationResult(
                "id", "test-vm", "Microsoft.Compute/virtualMachines",
                "rg", null,
                List.of(new OptimizationFinding(IssueType.OVERPROVISIONED, "Dev VM detected")),
                100.0, 50.0, "USD", de.orez.aura_sentry_core.model.CostSource.ESTIMATED,
                "Standard_D2s_v3", "westeurope", java.util.Map.of());
    }

    @BeforeEach
    void setUp() {
        lenient().when(credentialResolutionService.resolveCurrentUser())
                .thenThrow(new IllegalStateException("No authenticated user"));
        agent = new RemediationAgent(
                geminiApiClient, demoReportService,
                credentialResolutionService, null, null,
                demoModeProvider);
    }

    @Test
    void shouldReturnFallbackWhenGeminiFails() {
        when(demoModeProvider.isDemoMode()).thenReturn(false);
        when(geminiApiClient.callGemini(any()))
                .thenThrow(new RestClientException("Connection timeout"));

        AnalysisReportDto report = agent.advise(unhealthyResult(),
                RemediationAgent.AzureContext.empty());

        assertThat(report.summary()).contains("API error");
        assertThat(report.summary()).contains("Connection timeout");
    }

    @Test
    void shouldReturnFallbackWhenCredentialsMissing() {
        when(demoModeProvider.isDemoMode()).thenReturn(false);
        when(geminiApiClient.callGemini(any()))
                .thenThrow(new IllegalStateException("No credentials configured"));

        AnalysisReportDto report = agent.advise(unhealthyResult(),
                RemediationAgent.AzureContext.empty());

        assertThat(report.summary()).contains("Credential error");
    }

    @Test
    void shouldReturnHealthyReportForHealthyResource() {
        var healthyResult = new OptimizationResult(
                "id", "healthy-vm", "Microsoft.Compute/virtualMachines",
                "rg", null,
                List.of(new OptimizationFinding(IssueType.HEALTHY, "No issues")),
                50.0, 0.0, "USD", de.orez.aura_sentry_core.model.CostSource.ESTIMATED,
                "Standard_B1s", "westeurope", java.util.Map.of());

        when(demoModeProvider.isDemoMode()).thenReturn(false);

        AnalysisReportDto report = agent.advise(healthyResult,
                RemediationAgent.AzureContext.empty());

        assertThat(report.issueDetected()).isFalse();
        assertThat(report.issueType()).isEqualTo(IssueType.HEALTHY);
        verifyNoInteractions(geminiApiClient);
    }

    @Test
    void shouldUseDemoReportInDemoMode() {
        when(demoModeProvider.isDemoMode()).thenReturn(true);
        var demoReport = AnalysisReportDto.healthy("[DEMO] Mock report");
        when(demoReportService.createMockReport(any())).thenReturn(demoReport);

        AnalysisReportDto report = agent.advise(unhealthyResult(),
                RemediationAgent.AzureContext.empty());

        assertThat(report.summary()).contains("[DEMO]");
        verify(demoReportService).createMockReport(any());
        verifyNoInteractions(geminiApiClient);
    }

    @Test
    void shouldReturnParseErrorFallback() {
        when(demoModeProvider.isDemoMode()).thenReturn(false);
        when(geminiApiClient.callGemini(any()))
                .thenThrow(new RestClientException("Failed to parse Gemini response"));

        AnalysisReportDto report = agent.advise(unhealthyResult(),
                RemediationAgent.AzureContext.empty());

        assertThat(report.summary()).contains("API error");
    }
}
