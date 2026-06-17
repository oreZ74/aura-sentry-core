package de.orez.aura_sentry_core.advisor;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto;
import de.orez.aura_sentry_core.model.OptimizationResult;

/**
 * Deterministic mock report generator for demo mode.
 *
 * <p>Extracted from {@link RemediationAgent} to isolate ~250 lines of
 * hardcoded demo data. Each resource type receives a pre-crafted
 * {@link AnalysisReportDto} with realistic-looking remediation scripts
 * and savings estimates — no Gemini API quota is consumed.
 */
@Service
public class DemoReportService {

    private static final Logger log = LoggerFactory.getLogger(DemoReportService.class);

    public AnalysisReportDto createMockReport(OptimizationResult result) {
        String resourceType = result.resourceType() != null
                ? result.resourceType().toLowerCase() : "unknown";
        String resourceName = result.resourceName() != null ? result.resourceName() : "resource";

        boolean isHealthy = result.findings().stream()
                .allMatch(f -> f.flag() == AnalysisReportDto.IssueType.HEALTHY);
        if (isHealthy) {
            return AnalysisReportDto.healthy(
                    "[DEMO] Resource '%s' is optimally configured. No improvements needed in this simulation."
                            .formatted(resourceName));
        }

        return switch (resourceType) {
            case String t when t.contains("storage")           -> mockStorageReport(result, resourceName, t);
            case String t when t.contains("virtualmachines")   -> mockVmReport(result, resourceName, t);
            case String t when t.contains("publicipaddresses") -> mockPublicIpReport(result, resourceName, t);
            case String t when t.contains("loadbalancers")     -> mockLoadBalancerReport(result, resourceName, t);
            case String t when t.contains("disks")             -> mockDiskReport(result, resourceName, t);
            case String t when t.contains("postgresql")        -> mockPostgreSqlReport(result, resourceName, t);
            case String t when t.contains("sql")               -> mockSqlReport(result, resourceName, t);
            default -> mockGenericReport(result, resourceName, resourceType);
        };
    }

    private AnalysisReportDto mockStorageReport(OptimizationResult result, String name, String type) {
        String sku = result.sku() != null ? result.sku() : "Standard_LRS";
        boolean isGrs = sku.toUpperCase().contains("GRS");
        double savings = isGrs ? result.estimatedMonthlyCost() * 0.33 : result.estimatedMonthlyCost() * 0.10;
        String rg = result.resourceGroup();

        var scripts = isGrs
                ? new AnalysisReportDto.RemediationScripts(
                        "Downgrade storage account redundancy from GRS to LRS",
                        "az storage account update -n %s -g %s --sku Standard_LRS".formatted(name, rg),
                        """
                        resource "azurerm_storage_account" "optimized" {
                          name                     = "%s"
                          resource_group_name      = "%s"
                          account_tier             = "Standard"
                          account_replication_type = "LRS"
                        }""".formatted(name, rg),
                        "Set-AzStorageAccount -ResourceGroupName %s -Name %s -SkuName Standard_LRS".formatted(rg, name),
                        null,
                        List.of("Storage Account Contributor"))
                : new AnalysisReportDto.RemediationScripts(
                        "Enable lifecycle management on storage account",
                        "az storage account management-policy update -n %s -g %s --policy @lifecycle.json".formatted(name, rg),
                        null,
                        null,
                        null,
                        List.of("Storage Account Contributor"));

        return new AnalysisReportDto(
                "[DEMO] Storage account '%s' uses %s. %s".formatted(name, sku,
                        isGrs ? "Consider switching to LRS for non-critical data to save ~33%%."
                                : "LRS is cost-optimal. Enable lifecycle management for old blobs."),
                true,
                AnalysisReportDto.IssueType.COST_OPTIMIZATION,
                savings,
                isGrs ? AnalysisReportDto.Severity.MEDIUM : AnalysisReportDto.Severity.LOW,
                scripts,
                List.of(isGrs ? "Switch to LRS for dev/test workloads" : "Set blob tier to Cool after 30 days"));
    }

    private AnalysisReportDto mockVmReport(OptimizationResult result, String name, String type) {
        String sku = result.sku() != null ? result.sku() : "Standard_D2s_v3";
        double savings = result.estimatedMonthlyCost() * 0.50;
        String rg = result.resourceGroup();

        var scripts = new AnalysisReportDto.RemediationScripts(
                "Resize VM to smaller burstable SKU",
                "az vm resize -n %s -g %s --size Standard_B2s".formatted(name, rg),
                """
                resource "azurerm_virtual_machine" "vm" {
                  name                  = "%s"
                  resource_group_name   = "%s"
                  vm_size               = "Standard_B2s"
                  delete_os_disk_on_termination = true
                }""".formatted(name, rg),
                "Stop-AzVM -ResourceGroupName %s -Name %s -Force\n$vm = Get-AzVM -ResourceGroupName %s -Name %s\n$vm.HardwareProfile.VmSize = 'Standard_B2s'\nUpdate-AzVM -ResourceGroupName %s -VM $vm".formatted(rg, name, rg, name, rg),
                null,
                List.of("Virtual Machine Contributor"));

        return new AnalysisReportDto(
                "[DEMO] VM '%s' runs on %s. Consider right-sizing to a burstable B-series SKU for dev workloads.".formatted(name, sku),
                true,
                AnalysisReportDto.IssueType.OVERPROVISIONED,
                savings,
                AnalysisReportDto.Severity.HIGH,
                scripts,
                List.of("Right-size to Standard_B2s (~$30/mo)", "Enable auto-shutdown for dev VMs", "Review CPU/memory metrics over 14 days"));
    }

    private AnalysisReportDto mockPublicIpReport(OptimizationResult result, String name, String type) {
        String rg = result.resourceGroup();
        var scripts = new AnalysisReportDto.RemediationScripts(
                "Delete orphaned public IP to stop incurring charges",
                "az network public-ip delete -n %s -g %s".formatted(name, rg),
                """
                resource "azurerm_public_ip" "orphaned" {
                  name                = "%s"
                  resource_group_name = "%s"
                  # Removing this resource – set count = 0 or delete from state
                }""".formatted(name, rg),
                "Remove-AzPublicIpAddress -Name %s -ResourceGroupName %s -Force".formatted(name, rg),
                """
                resource pip '%s' = {
                  name: '%s'
                  # Remove unused public IP from deployment
                }""".formatted(name, name),
                List.of("Network Contributor"));

        return new AnalysisReportDto(
                "[DEMO] Public IP '%s' is unattached and costing ~$%.2f/mo. Delete it if no longer needed.".formatted(name, result.estimatedMonthlyCost()),
                true,
                AnalysisReportDto.IssueType.ORPHANED,
                result.estimatedMonthlyCost(),
                AnalysisReportDto.Severity.MEDIUM,
                scripts,
                List.of("Verify no DNS records reference this IP", "Delete to stop charges immediately"));
    }

    private AnalysisReportDto mockLoadBalancerReport(OptimizationResult result, String name, String type) {
        String rg = result.resourceGroup();
        var scripts = new AnalysisReportDto.RemediationScripts(
                "Delete idle load balancer with empty backend pool",
                "az network lb delete -n %s -g %s".formatted(name, rg),
                null,
                "Remove-AzLoadBalancer -Name %s -ResourceGroupName %s -Force".formatted(name, rg),
                null,
                List.of("Network Contributor"));

        return new AnalysisReportDto(
                "[DEMO] Load Balancer '%s' in dev environment appears idle. Consider deleting or downgrading to Basic tier.".formatted(name),
                true,
                AnalysisReportDto.IssueType.GOVERNANCE_MISSING,
                result.estimatedMonthlyCost(),
                AnalysisReportDto.Severity.MEDIUM,
                scripts,
                List.of("Verify no backend pool members", "Switch to Basic LB (free) for dev workloads"));
    }

    private AnalysisReportDto mockDiskReport(OptimizationResult result, String name, String type) {
        String rg = result.resourceGroup();
        var scripts = new AnalysisReportDto.RemediationScripts(
                "Delete orphaned managed disk to stop premium storage charges",
                "az disk delete -n %s -g %s --yes".formatted(name, rg),
                null,
                "Remove-AzDisk -DiskName %s -ResourceGroupName %s -Force".formatted(name, rg),
                null,
                List.of("Disk Operator", "Virtual Machine Contributor"));

        return new AnalysisReportDto(
                "[DEMO] Managed Disk '%s' appears orphaned – not attached to any VM. Delete to save ~$%.2f/mo.".formatted(name, result.estimatedMonthlyCost()),
                true,
                AnalysisReportDto.IssueType.ORPHANED,
                result.estimatedMonthlyCost(),
                AnalysisReportDto.Severity.HIGH,
                scripts,
                List.of("Verify no snapshots depend on this disk", "Export data before deletion if needed"));
    }

    private AnalysisReportDto mockPostgreSqlReport(OptimizationResult result, String name, String type) {
        double savings = result.estimatedMonthlyCost() * 0.30;
        String rg = result.resourceGroup();
        var scripts = new AnalysisReportDto.RemediationScripts(
                "Scale down PostgreSQL server to Burstable tier for lighter workloads",
                "az postgres server update -n %s -g %s --sku-name B_Gen5_1 --tier Basic".formatted(name, rg),
                null,
                null,
                null,
                List.of("PostgreSQL Contributor"));

        return new AnalysisReportDto(
                "[DEMO] PostgreSQL '%s' uses %s (General Purpose). Consider Burstable tier for lighter workloads.".formatted(name, result.sku()),
                true,
                AnalysisReportDto.IssueType.OVERPROVISIONED,
                savings,
                AnalysisReportDto.Severity.MEDIUM,
                scripts,
                List.of("Monitor CPU usage over 7 days", "Consider Burstable B1ms tier (~$30/mo)"));
    }

    private AnalysisReportDto mockSqlReport(OptimizationResult result, String name, String type) {
        double savings = result.estimatedMonthlyCost() * 0.70;
        String rg = result.resourceGroup();
        var scripts = new AnalysisReportDto.RemediationScripts(
                "Downgrade SQL Database from Premium to Standard tier",
                "az sql db update -n %s -s myserver -g %s --edition Standard --service-objective S3".formatted(name, rg),
                """
                resource "azurerm_mssql_database" "db" {
                  name      = "%s"
                  server_id = azurerm_mssql_server.server.id
                  sku_name  = "S3"
                }""".formatted(name),
                "Set-AzSqlDatabase -DatabaseName %s -ServerName myserver -ResourceGroupName %s -Edition Standard -RequestedServiceObjectiveName S3".formatted(name, rg),
                null,
                List.of("SQL DB Contributor"));

        return new AnalysisReportDto(
                "[DEMO] SQL Database '%s' uses Premium tier (%s). Downgrading to Standard can save ~70%%.".formatted(name, result.sku()),
                true,
                AnalysisReportDto.IssueType.COST_OPTIMIZATION,
                savings,
                AnalysisReportDto.Severity.HIGH,
                scripts,
                List.of("Review DTU/CPU utilization before downgrading", "Test with Standard tier in staging first"));
    }

    private AnalysisReportDto mockGenericReport(OptimizationResult result, String name, String type) {
        var scripts = new AnalysisReportDto.RemediationScripts(
                "Inspect resource details and add required tags",
                "az resource show --ids %s".formatted(result.resourceId()),
                null,
                null,
                null,
                List.of("Reader"));

        return new AnalysisReportDto(
                "[DEMO] Resource '%s' (%s) requires review. In production, Gemini would analyse the real configuration.".formatted(name, type),
                true,
                AnalysisReportDto.IssueType.GOVERNANCE_MISSING,
                result.estimatedMonthlyCost() * 0.10,
                AnalysisReportDto.Severity.LOW,
                scripts,
                List.of("[DEMO] Add resource tags for ownership tracking", "[DEMO] Review resource usage patterns"));
    }
}
