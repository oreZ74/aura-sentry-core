package de.orez.aura_sentry_core.advisor;

import java.util.Map;
import java.util.stream.Collectors;

import de.orez.aura_sentry_core.model.CostSource;
import de.orez.aura_sentry_core.model.OptimizationFinding;
import de.orez.aura_sentry_core.model.OptimizationResult;

/**
 * Builds a structured, deterministic prompt from an {@link OptimizationResult}.
 * Stateless and free of framework dependencies – directly unit-testable.
 *
 * Modern Java 25 implementation:
 * - Text blocks with variable interpolation
 * - Stream API for tag formatting
 * - Pattern matching on resource findings
 *
 * The KI (Gemini) now receives:
 * - Location context (region)
 * - Business context (tags, cost data)
 * - SKU-specific remediation suggestions
 */
final class RemediationPromptBuilder {

    /**
     * Shortened, human-readable version of {@link #SYSTEM_PROMPT} shown as
     * a placeholder in the AI Advisor Configuration modal when the user has
     * not defined a custom system template.
     *
     * <p>Must contain the available {@code {{placeholders}}} so users know
     * which variables they can interpolate into their custom prompts.
     */
    public static final String PLACEHOLDER_TEMPLATE = """
            Default System Prompt (active when this field is empty):

            You are a Senior Cloud Engineer with deep expertise in Azure, Terraform, Azure PowerShell, Bicep, and Azure CLI.
            You analyse cloud resource configurations and respond with structured JSON matching this schema:

            {
              "summary": "Brief root-cause analysis (max 2 sentences)",
              "issueDetected": true,
              "issueType": "HEALTHY | GOVERNANCE_MISSING | OVERPROVISIONED | UNDERUTILIZED | ORPHANED | SECURITY_RISK | COST_OPTIMIZATION",
              "savingsPotentialUsd": 0.0,
              "severity": "NONE | LOW | MEDIUM | HIGH | CRITICAL",
              "remediationScripts": {
                "description": "What the remediation accomplishes (one sentence)",
                "azureCli": "Azure CLI script or command",
                "terraform": "Terraform HCL resource block",
                "azurePowerShell": "Azure PowerShell script",
                "bicep": "Bicep module definition",
                "requiredRbacRoles": ["Virtual Machine Contributor", "Storage Account Contributor"]
              },
              "recommendations": ["Concrete action item 1", "action item 2"]
            }

            Available placeholders for custom prompts:
              {{resourceName}}  {{resourceType}}  {{resourceGroup}}  {{region}}  {{sku}}  {{state}}
              {{flag}}  {{reason}}  {{tags}}  {{cost}}  {{costSource}}

            Leave this field empty to use the full default system prompt (90+ lines with detailed
            classification rules, telemetry analysis, and deterministic issue classification).""";

    static final String SYSTEM_PROMPT = """
            You are a Senior Cloud Engineer with deep expertise in Azure, Terraform, Azure PowerShell, Bicep, and Azure CLI.
            Your role is to analyse cloud resource configurations and provide cost-optimization recommendations.

            ═══════════════════════ CRITICAL – OUTPUT FORMAT ═══════════════════════
            You MUST respond with a SINGLE, VALID JSON object. No Markdown.
            No code fences (```). No explanations outside the JSON.
            The JSON MUST match this exact schema:

            {
              "summary": "Brief human-readable root-cause analysis (max 2 sentences)",
              "issueDetected": true or false,
              "issueType": "HEALTHY" | "GOVERNANCE_MISSING" | "OVERPROVISIONED" | "UNDERUTILIZED" | "ORPHANED" | "SECURITY_RISK" | "COST_OPTIMIZATION",
              "savingsPotentialUsd": 0.0,
              "severity": "NONE" | "LOW" | "MEDIUM" | "HIGH" | "CRITICAL",
              "remediationScripts": {
                "description": "What the remediation accomplishes (one sentence)",
                "azureCli": "string (Azure CLI script/command as a single string with \\n for line breaks) – required when issueDetected=true",
                "terraform": "string (Terraform HCL resource definition as a single string with \\n for line breaks) – provide when applicable",
                "azurePowerShell": "string (Azure PowerShell script as a single string with \\n for line breaks) – provide when applicable",
                "bicep": "string (Bicep module definition as a single string with \\n for line breaks) – provide when applicable",
                "requiredRbacRoles": ["List of minimal Azure RBAC roles needed, e.g. 'Virtual Machine Contributor', 'Storage Account Contributor'"]
              },
              "recommendations": ["List of concrete action items"]
            }

            IMPORTANT: Set unused IaC language fields to null (not empty string) when the language is not applicable for the given remediation.

            ═══════════════════════ STRICT CLASSIFICATION RULES ═══════════════════════
            You MUST base your issueType strictly on these rules. Do not fluctuate
            between runs for the same resource – your output MUST be deterministic.

            HEALTHY:
            - Resource is highly optimized OR currently on the absolute lowest cost tier
              for its type (e.g. Standard_LRS for storage, Basic/B1 for compute) AND
              no further financial optimisation is possible.
            - Dev/Test environments on minimum tiers are HEALTHY by definition because
              downgrading further is impossible.
            - Unattached disks on Ultra/Premium SSD → NOT healthy (could downgrade to Standard).
            - Unattached disks on Standard HDD / Standard_LRS → HEALTHY (already minimum cost).

            ORPHANED:
            - Resource has zero usage (0 CPU, 0 network, or no VM attachment) over 7+ days
              AND is NOT on the lowest cost tier, indicating it was forgotten.
            - Unattached Public IPs not associated with any load balancer or NIC.
            - Idle Load Balancers with empty backend pools AND no inbound rules.
            - An orphaned resource on the lowest tier should still be flagged ORPHANED
              because even minimal cost is wasted money for an unused resource.

            OVERPROVISIONED:
            - Resource is active but has severely mismatched SKU utilisation.
            - VM with <5% CPU over 7 days but running on a non-burstable SKU.
            - Premium SSD with <100 IOPS actual usage over 7 days.
            - General Purpose database with <10% DTU/CPU utilisation.

            GOVERNANCE_MISSING:
            - Resource lacks essential tags (Environment, Owner, CostCenter).
            - No auto-shutdown configured for Dev VMs.
            - No backup/lifecycle policy configured for storage accounts.
            - No diagnostic settings enabled.

            COST_OPTIMIZATION:
            - Resource is active and correctly sized but region pricing arbitrage exists.
            - Reserved Instance commitment would save 30%+ on a steady-state VM.
            - Storage account using GRS/RA-GRS where LRS would be sufficient.

            ═══════ ABSOLUTE OVERRIDE RULE regarding Utilization ═══════
            If a resource shows absolutely ZERO utilization (e.g., 0 Bytes Network In/Out, 0% CPU) over the observed period, you MUST NEVER classify it as HEALTHY.
            - Even if the cost is $0.00.
            - Even if it is on the cheapest tier (e.g., Standard_LRS).
            - Even if it is a Dev/Test environment.
            In these cases of zero activity, you MUST classify it as 'ORPHANED' (if it appears abandoned) or 'UNDERUTILIZED'. A healthy resource is a USED resource.

            ═══════════════════════ RULES ═══════════════════════
            - If the resource is healthy: issueDetected=false, issueType="HEALTHY",
              severity="NONE", savingsPotentialUsd=0.0, remediationScripts={"description":"Resource is healthy.","azureCli":null,"terraform":null,"azurePowerShell":null,"bicep":null,"requiredRbacRoles":[]}.
            - When an issue is detected, ALWAYS provide at least azureCli.
            - Provide terraform, azurePowerShell, and bicep scripts when the remediation
              can be expressed in those languages. Set to null if not applicable.
            - Each code field must be a single string with literal \\n for line breaks.
            - ALL four script fields (azureCli, terraform, azurePowerShell, bicep) MUST be present as keys even when null.
            - savingsPotentialUsd must be a number (not a string), 0.0 when no savings.
            - Consider the resource's region (pricing varies by location) and SKU/tier.
            - Use tag-based business context (e.g. "Environment: Dev" → relaxed SLAs).
            - Respond with raw JSON only – no wrapping, no conversational text.
            - The static scan engine pre-flagged this resource. If you disagree with the
              detected issue and mark it HEALTHY, briefly explain in the summary why you
              are overriding the static scanner's assessment.
            - requiredRbacRoles MUST be an array of strings listing the minimal Azure RBAC
              role names (e.g. "Virtual Machine Contributor", "Network Contributor") that
              a service principal needs to execute these scripts. Use actual Azure RBAC
              role names. Empty array when no remediation is needed.

            ═══════ ABSOLUTE RULE FOR SAVINGS ═══════
            The 'savingsPotentialUsd' MUST NOT exceed the current monthly cost of the
            resource (provided in the 'Current Monthly Cost' field below). If the current
            cost is 0.00, the savings MUST be exactly 0.00. Do not invent hypothetical
            savings. The savings value represents ACTUAL achievable monthly reduction
            based on the current billing amount – never more.
            """;

    private RemediationPromptBuilder() {
    }

    static String buildUserPrompt(OptimizationResult result) {
        return buildUserPrompt(result, null);
    }

    static String buildUserPrompt(OptimizationResult result, RemediationAgent.AzureContext context) {
        return buildUserPrompt(result, context, result.estimatedMonthlyCost());
    }

    /**
     * Builds the user prompt with optional extended Azure context.
     *
     * @param result  the resource analysis result
     * @param context optional extended metadata (may be {@code null})
     * @param costMtd the actual current monthly cost – used as hard ceiling for savings
     */
    static String buildUserPrompt(OptimizationResult result, RemediationAgent.AzureContext context, double costMtd) {
        String findingsList = result.findings().stream()
                .map(RemediationPromptBuilder::formatFinding)
                .collect(Collectors.joining("\n"));

        String tagsFormatted = formatTags(result.tags());
        String costAnalysis = formatCostAnalysis(result);
        String costSectionTitle = costSectionTitle(result.costSource());
        String extendedContext = formatExtendedContext(context);

        return """
                ## Ressource zur Analyse

                - **ID:** %s
                - **Name:** %s
                - **Typ:** %s
                - **SKU / Tier:** %s
                - **Region/Location:** %s
                - **Ressourcengruppe:** %s
                - **Status:** %s

                ## Business-Kontext (Tags)

                %s

            ## %s

%s

                ## Erkannte Probleme

                %s
                %s""".formatted(
                result.resourceId(),
                result.resourceName(),
                result.resourceType(),
                result.sku() != null ? result.sku() : "N/A",
                result.region() != null ? result.region() : "Unknown",
                result.resourceGroup(),
                result.state(),
                tagsFormatted,
                costSectionTitle,
                costAnalysis,
                findingsList,
                extendedContext);
    }

    private static String formatFinding(OptimizationFinding finding) {
        return "- **[%s]** %s".formatted(finding.flag().name(), finding.reason());
    }

    /**
     * Formats resource tags into a readable structure for the KI.
     * Helps the KI understand business context (Environment, Owner, CostCenter,
     * etc.).
     */
    private static String formatTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "Keine Tags definiert.";
        }

        return tags.entrySet().stream()
                .map(e -> "- **%s:** `%s`".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Formats cost and savings data with the correct provenance label.
     *
     * <p>Includes a hard ceiling warning so the AI knows the absolute
     * maximum for {@code savingsPotentialUsd}.
     */
    private static String formatCostAnalysis(OptimizationResult result) {
        double monthlyCost = result.estimatedMonthlyCost();
        double monthlySavings = result.estimatedMonthlySavings();
        double annualSavings = monthlySavings * 12;

        String sourceLabel = switch (result.costSource()) {
            case ACTUAL_BILLING -> "Azure Cost Management API (Month-to-Date)";
            case ESTIMATED -> "PricingEngine heuristic estimate";
            case UNAVAILABLE -> "Unavailable (API error or no billing record yet)";
            case AMORTIZED -> "Azure Cost Management API (Amortized)";
        };

        String savingsFormatted = monthlySavings > 0.01
                ? "**$%.2f/month** ($%.2f/year)".formatted(monthlySavings, annualSavings)
                : "No optimization potential identified.";

        return """
                - **Data Source:** %s
                - **Current Monthly Cost:** $%.2f USD  ← THIS IS THE ABSOLUTE CEILING for savingsPotentialUsd
                - **Potential Monthly Savings (static estimate):** %s
                - **Optimization Basis:** SKU downgrade, regional pricing arbitrage
                - **HARD RULE:** savingsPotentialUsd must be <= %.2f (the current monthly cost). If cost is $0.00, savings MUST be 0.00.
                """
                .formatted(sourceLabel, monthlyCost, savingsFormatted, monthlyCost)
                .strip();
    }

    /**
     * Returns the correct section title based on cost provenance.
     */
    private static String costSectionTitle(CostSource source) {
        return switch (source) {
            case ACTUAL_BILLING -> "FinOps & Cost Analysis (Actual Billing Data – Month-to-Date)";
            case AMORTIZED -> "FinOps & Cost Analysis (Amortized Billing Data)";
            case ESTIMATED -> "FinOps & Cost Analysis (Estimated – Heuristic PricingEngine)";
            case UNAVAILABLE -> "FinOps & Cost Analysis (Unavailable – No billing data)";
        };
    }

    /**
     * Formats the Performance Telemetry section for the AI prompt.
     * Includes CPU utilization, network traffic, and underutilization flag
     * from Azure Monitor metrics.
     *
     * <p>Only non-null fields are included – keeps the prompt lean when
     * no telemetry data is available.
     */
    private static String formatExtendedContext(RemediationAgent.AzureContext ctx) {
        if (ctx == null) {
            return "";
        }

        // Check if we have any actual telemetry data
        boolean hasTelemetry = ctx.averageCpuPercent() != null
                || ctx.networkInBytes() != null
                || ctx.networkOutBytes() != null
                || ctx.isUnderutilized() != null;

        if (!hasTelemetry) {
            return "";
        }

        var sb = new StringBuilder();
        sb.append("\n## Performance Telemetry (Last 7 Days)\n\n");

        if (ctx.averageCpuPercent() != null) {
            sb.append("- **Average CPU Utilization:** ")
              .append("%.1f%%".formatted(ctx.averageCpuPercent()))
              .append("\n");
        }
        if (ctx.networkInBytes() != null) {
            sb.append("- **Network Ingress:** ")
              .append(formatBytes(ctx.networkInBytes()))
              .append("\n");
        }
        if (ctx.networkOutBytes() != null) {
            sb.append("- **Network Egress:** ")
              .append(formatBytes(ctx.networkOutBytes()))
              .append("\n");
        }
        if (ctx.isUnderutilized() != null) {
            sb.append("- **Underutilization Flag:** ")
              .append(ctx.isUnderutilized() ? "true (CPU < 5% over 7 days)" : "false")
              .append("\n");
        }

        // Legacy fields (for backward compatibility)
        if (ctx.lastAccessTime() != null) {
            sb.append("- **Last Access Time:** ").append(ctx.lastAccessTime()).append("\n");
        }
        if (ctx.iops() != null) {
            sb.append("- **Provisioned IOPS:** ").append(ctx.iops()).append("\n");
        }
        if (ctx.throughputMbps() != null) {
            sb.append("- **Provisioned Throughput:** ").append(ctx.throughputMbps()).append(" Mbps\n");
        }
        if (ctx.extendedProperties() != null && !ctx.extendedProperties().isEmpty()) {
            ctx.extendedProperties().forEach((k, v) ->
                    sb.append("- **").append(k).append(":** `").append(v).append("`\n"));
        }

        return sb.toString();
    }

    /**
     * Formats byte values into human-readable units (KB, MB, GB).
     */
    private static String formatBytes(double bytes) {
        if (bytes < 1024) {
            return "%.0f Bytes".formatted(bytes);
        } else if (bytes < 1024 * 1024) {
            return "%.1f KB".formatted(bytes / 1024);
        } else if (bytes < 1024 * 1024 * 1024) {
            return "%.1f MB".formatted(bytes / (1024 * 1024));
        } else {
            return "%.2f GB".formatted(bytes / (1024 * 1024 * 1024));
        }
    }
}
