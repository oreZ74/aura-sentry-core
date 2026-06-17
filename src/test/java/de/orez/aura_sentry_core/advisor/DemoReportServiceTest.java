package de.orez.aura_sentry_core.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto;
import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.OptimizationResult;
import de.orez.aura_sentry_core.model.ResourceState;

class DemoReportServiceTest {

    private DemoReportService service;

    @BeforeEach
    void setUp() {
        service = new DemoReportService();
    }

    @Test
    void shouldGenerateStorageReport() {
        var result = buildResult("stor01", "microsoft.storage/storageaccounts", "Standard_GRS");
        AnalysisReportDto report = service.createMockReport(result);
        assertThat(report.issueType()).isEqualTo(IssueType.COST_OPTIMIZATION);
        assertThat(report.summary()).contains("[DEMO]");
    }

    @Test
    void shouldGenerateVmReport() {
        var result = buildResult("vm01", "microsoft.compute/virtualMachines", "Standard_D2s_v3");
        AnalysisReportDto report = service.createMockReport(result);
        assertThat(report.issueType()).isEqualTo(IssueType.OVERPROVISIONED);
        assertThat(report.summary()).contains("VM");
    }

    @Test
    void shouldGeneratePublicIpReport() {
        var result = buildResult("pip01", "microsoft.network/publicIPAddresses", "Standard");
        AnalysisReportDto report = service.createMockReport(result);
        assertThat(report.issueType()).isEqualTo(IssueType.ORPHANED);
        assertThat(report.summary()).contains("Public IP");
    }

    @Test
    void shouldGenerateLoadBalancerReport() {
        var result = buildResult("lb01", "microsoft.network/loadBalancers", "Standard");
        AnalysisReportDto report = service.createMockReport(result);
        assertThat(report.issueType()).isEqualTo(IssueType.GOVERNANCE_MISSING);
        assertThat(report.summary()).contains("Load Balancer");
    }

    @Test
    void shouldGenerateDiskReport() {
        var result = buildResult("disk01", "microsoft.compute/disks", "Premium_LRS");
        AnalysisReportDto report = service.createMockReport(result);
        assertThat(report.issueType()).isEqualTo(IssueType.ORPHANED);
        assertThat(report.summary()).contains("Disk");
    }

    @Test
    void shouldGeneratePostgreSqlReport() {
        var result = buildResult("pg01", "microsoft.dbforpostgresql/servers", "GP_Gen5_2");
        AnalysisReportDto report = service.createMockReport(result);
        assertThat(report.issueType()).isEqualTo(IssueType.OVERPROVISIONED);
        assertThat(report.summary()).contains("PostgreSQL");
    }

    @Test
    void shouldGenerateSqlReport() {
        var result = buildResult("sql01", "microsoft.sql/servers", "Premium");
        AnalysisReportDto report = service.createMockReport(result);
        assertThat(report.issueType()).isEqualTo(IssueType.COST_OPTIMIZATION);
        assertThat(report.summary()).contains("SQL");
    }

    @Test
    void shouldGenerateGenericReportForUnknownType() {
        var result = buildResult("unk01", "microsoft.unknown/unknownType", "Basic");
        AnalysisReportDto report = service.createMockReport(result);
        assertThat(report.issueType()).isEqualTo(IssueType.GOVERNANCE_MISSING);
        assertThat(report.summary()).contains("[DEMO]");
    }

    @Test
    void shouldReturnHealthyForHealthyResource() {
        var result = new OptimizationResult(
                "id", "healthy", "microsoft.compute/virtualMachines",
                "rg", ResourceState.RUNNING,
                List.of(new OptimizationFinding(IssueType.HEALTHY, "No issues")),
                0.0, 0.0, "USD", de.orez.aura_sentry_core.model.CostSource.ESTIMATED,
                "Standard_B1s", "westeurope", java.util.Map.of());

        AnalysisReportDto report = service.createMockReport(result);
        assertThat(report.issueDetected()).isFalse();
        assertThat(report.issueType()).isEqualTo(IssueType.HEALTHY);
    }

    private static OptimizationResult buildResult(String name, String type, String sku) {
        return new OptimizationResult(
                "/subscriptions/sub/resourceGroups/rg/providers/" + type + "/" + name,
                name, type, "rg", ResourceState.RUNNING,
                List.of(new OptimizationFinding(IssueType.GOVERNANCE_MISSING, "Missing tags")),
                50.0, 10.0, "USD", de.orez.aura_sentry_core.model.CostSource.ESTIMATED,
                sku, "westeurope", java.util.Map.of());
    }
}
