package de.orez.aura_sentry_core.advisor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.model.CostSource;
import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.OptimizationResult;
import de.orez.aura_sentry_core.model.ResourceState;

/**
 * Unit tests for RemediationPromptBuilder.
 *
 * Validates:
 * - Prompt structure and completeness
 * - Markdown formatting for KI context
 * - Inclusion of cost analysis and metadata
 * - Graceful handling of missing data
 */
@DisplayName("RemediationPromptBuilder KI Prompt Generation Tests")
class RemediationPromptBuilderTests {

    private static final CostSource DEFAULT_SOURCE = CostSource.ESTIMATED;

    /**
     * Test: System prompt contains essential KI context
     * Validates that the KI understands cost optimization, regional impact, and SKU
     * considerations
     */
    @Test
    @DisplayName("System prompt includes cost optimization guidance")
    void testSystemPromptContainsOptimizationGuidance() {
        String systemPrompt = RemediationPromptBuilder.SYSTEM_PROMPT;

        assertTrue(systemPrompt.contains("cost") || systemPrompt.contains("Cost"),
                "System prompt should mention cost optimization");
        assertTrue(
                systemPrompt.contains("region") || systemPrompt.contains("Region") || systemPrompt.contains("location"),
                "System prompt should mention regional considerations");
        assertTrue(systemPrompt.contains("SKU") || systemPrompt.contains("tier"),
                "System prompt should mention SKU/tier optimization");
        assertTrue(systemPrompt.contains("Markdown"),
                "System prompt should specify Markdown format");
    }

    /**
     * Test: User prompt includes resource metadata
     * Tags, region, and cost data should be integrated
     */
    @Test
    @DisplayName("User prompt includes complete resource metadata")
    void testUserPromptMetadataIntegration() {
        OptimizationResult result = new OptimizationResult(
                "/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.Storage/storageAccounts/storage1",
                "storage1",
                "Microsoft.Storage/storageAccounts",
                "rg1",
                ResourceState.RUNNING,
                List.of(new OptimizationFinding(IssueType.COST_OPTIMIZATION, "Using GRS instead of LRS")),
                50.0, 15.0, "$",
                DEFAULT_SOURCE,
                "Standard_GRS",
                "westeurope",
                Map.of(
                        "Environment", "production",
                        "CostCenter", "engineering",
                        "Owner", "team-cloud"));

        String userPrompt = RemediationPromptBuilder.buildUserPrompt(result);

        assertTrue(userPrompt.contains("storage1"), "Prompt should include resource name");
        assertTrue(userPrompt.contains("Microsoft.Storage/storageAccounts"), "Prompt should include resource type");
        assertTrue(userPrompt.contains("westeurope"), "Prompt should include region");
        assertTrue(userPrompt.contains("Standard_GRS"), "Prompt should include SKU");
        assertTrue(userPrompt.contains("50"), "Prompt should include monthly cost");
        assertTrue(userPrompt.contains("15"), "Prompt should include savings potential");
        assertTrue(userPrompt.contains("production"), "Prompt should include business tags");
        assertTrue(userPrompt.contains("engineering"), "Prompt should include CostCenter tag");
    }

    @Test
    @DisplayName("User prompt handles unknown region gracefully")
    void testMissingRegionHandling() {
        OptimizationResult result = new OptimizationResult(
                "id1", "name1", "type1", "rg1", ResourceState.RUNNING,
                List.of(new OptimizationFinding(IssueType.HEALTHY, "OK")),
                10.0, 0.0, "$",
                DEFAULT_SOURCE,
                "Standard", null,
                Map.of());

        String userPrompt = RemediationPromptBuilder.buildUserPrompt(result);

        assertTrue(userPrompt.contains("Unknown") || userPrompt.contains("location"),
                "Prompt should indicate missing region information");
    }

    @Test
    @DisplayName("User prompt handles missing tags gracefully")
    void testMissingTagsHandling() {
        OptimizationResult result = new OptimizationResult(
                "id1", "name1", "type1", "rg1", ResourceState.RUNNING,
                List.of(new OptimizationFinding(IssueType.HEALTHY, "OK")),
                10.0, 0.0, "$",
                DEFAULT_SOURCE,
                "Standard", "westeurope",
                Map.of());

        String userPrompt = RemediationPromptBuilder.buildUserPrompt(result);

        assertTrue(userPrompt.contains("Keine Tags") || userPrompt.toLowerCase().contains("tag"),
                "Prompt should indicate absence of tags");
    }

    @Test
    @DisplayName("Cost analysis clearly communicates savings potential")
    void testSavingsCommunication() {
        OptimizationResult highSavings = new OptimizationResult(
                "id1", "vm1", "VM", "rg1", ResourceState.RUNNING,
                List.of(new OptimizationFinding(IssueType.COST_OPTIMIZATION, "Oversized")),
                200.0, 100.0, "$",
                DEFAULT_SOURCE,
                "Standard_D8s", "westeurope", Map.of());

        String prompt1 = RemediationPromptBuilder.buildUserPrompt(highSavings);
        assertTrue(prompt1.contains("100"), "Prompt should include high savings amount");
        assertTrue(prompt1.contains("1200") || prompt1.contains("1200.00"),
                "Prompt should include annual savings");

        OptimizationResult noSavings = new OptimizationResult(
                "id2", "storage1", "Storage", "rg1", ResourceState.RUNNING,
                List.of(new OptimizationFinding(IssueType.HEALTHY, "Optimal")),
                5.0, 0.0, "$",
                DEFAULT_SOURCE,
                "Standard_LRS", "eastus", Map.of());

        String prompt2 = RemediationPromptBuilder.buildUserPrompt(noSavings);
        assertTrue(prompt2.contains("0") || prompt2.toLowerCase().contains("kein") || prompt2.contains("not"),
                "Prompt should indicate zero savings");
    }

    @Test
    @DisplayName("Multiple optimization findings are formatted correctly")
    void testMultipleFindingsFormatting() {
        List<OptimizationFinding> findings = List.of(
                new OptimizationFinding(IssueType.COST_OPTIMIZATION, "Reason 1"),
                new OptimizationFinding(IssueType.ORPHANED, "Reason 2"));

        OptimizationResult result = new OptimizationResult(
                "id1", "res1", "type1", "rg1", ResourceState.RUNNING,
                findings, 10.0, 5.0, "$",
                DEFAULT_SOURCE,
                "SKU", "region", Map.of());

        String prompt = RemediationPromptBuilder.buildUserPrompt(result);

        assertTrue(prompt.contains("COST_OPTIMIZATION"), "Prompt should include all findings");
        assertTrue(prompt.contains("ORPHANED"), "Prompt should include all findings");
        assertTrue(prompt.contains("Reason 1"), "Prompt should include finding reasons");
        assertTrue(prompt.contains("Reason 2"), "Prompt should include finding reasons");
    }

    @Test
    @DisplayName("Regional metadata enables location-specific KI recommendations")
    void testRegionalContextIntegration() {
        OptimizationResult premiumRegion = new OptimizationResult(
                "id1", "res1", "Microsoft.Sql/servers", "rg1", ResourceState.RUNNING,
                List.of(new OptimizationFinding(IssueType.COST_OPTIMIZATION, "Oversized database")),
                300.0, 100.0, "$",
                DEFAULT_SOURCE,
                "Premium", "japaneast",
                Map.of("Environment", "production"));

        String prompt = RemediationPromptBuilder.buildUserPrompt(premiumRegion);

        assertTrue(prompt.contains("japaneast"), "Prompt should include premium region info");
        assertTrue(prompt.contains("300"), "Prompt should include regional pricing impact");
    }

    @Test
    @DisplayName("User prompt is valid Markdown")
    void testMarkdownValidity() {
        OptimizationResult result = new OptimizationResult(
                "id1", "res1", "type1", "rg1", ResourceState.RUNNING,
                List.of(new OptimizationFinding(IssueType.HEALTHY, "Looks good")),
                10.0, 0.0, "$",
                DEFAULT_SOURCE,
                "Standard", "westeurope", Map.of("key", "value"));

        String prompt = RemediationPromptBuilder.buildUserPrompt(result);

        assertTrue(prompt.contains("##"), "Prompt should use Markdown h2 headers");
        assertTrue(prompt.contains("**"), "Prompt should use Markdown bold formatting");
        assertTrue(prompt.contains("-"), "Prompt should use Markdown lists");
    }

    @Test
    @DisplayName("SKU tier is clearly communicated for optimization context")
    void testSkuHighlighting() {
        OptimizationResult result = new OptimizationResult(
                "id1", "storage1", "Storage", "rg1", ResourceState.RUNNING,
                List.of(new OptimizationFinding(IssueType.COST_OPTIMIZATION, "GRS → LRS")),
                50.0, 15.0, "$",
                DEFAULT_SOURCE,
                "Standard_GRS / Hot", "westeurope", Map.of());

        String prompt = RemediationPromptBuilder.buildUserPrompt(result);

        assertTrue(prompt.contains("Standard_GRS"), "Prompt should clearly show current SKU");
        assertTrue(prompt.contains("Hot") || prompt.contains("Tier"),
                "Prompt should include access tier information if available");
    }
}
