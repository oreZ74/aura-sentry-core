package de.orez.aura_sentry_core.advisor.dto;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Deterministic JSON schema for Gemini API responses.
 *
 * Replaces the previous free-form Markdown output with structured,
 * machine-readable data that the frontend renders in a clean tab interface.
 * Every Gemini prompt now enforces exactly this schema – no Markdown, no
 * backticks, no conversational filler.
 */
public record AnalysisReportDto(
        String summary,
        boolean issueDetected,
        IssueType issueType,
        double savingsPotentialUsd,
        Severity severity,
        RemediationScripts remediationScripts,
        List<String> recommendations) {

    /**
     * Compact constructor: ensures all nullable fields have safe defaults
     * when Gemini omits or nulls them in the JSON response.
     * Jackson's record support (2.12+) calls the canonical constructor,
     * which Java routes through this compact constructor automatically.
     */
    public AnalysisReportDto {
        if (summary == null) summary = "";
        if (issueType == null) issueType = IssueType.COST_OPTIMIZATION;
        if (severity == null) severity = Severity.NONE;
        if (remediationScripts == null) remediationScripts = RemediationScripts.EMPTY;
        if (recommendations == null) recommendations = List.of();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Nested enums – tolerant deserialization via @JsonCreator
    // ─────────────────────────────────────────────────────────────────────────────

    public enum IssueType {
        HEALTHY,
        GOVERNANCE_MISSING,
        POLICY_MISSING,
        OVERPROVISIONED,
        UNDERUTILIZED,
        ORPHANED,
        NEW_RESOURCE,
        SECURITY_RISK,
        COST_OPTIMIZATION;

        /**
         * Tolerant deserializer: accepts any string Gemini might produce.
         * Known aliases (POTENTIAL_WASTE, WASTE, etc.) are mapped to the
         * canonical {@link #COST_OPTIMIZATION} value. Unknown strings also
         * fall back to {@link #COST_OPTIMIZATION} to prevent
         * {@code InvalidFormatException} from crashing the pipeline.
         */
        @JsonCreator
        public static IssueType fromString(String value) {
            if (value == null || value.isBlank()) {
                return COST_OPTIMIZATION;
            }
            String upper = value.trim().toUpperCase(Locale.ROOT);
            return switch (upper) {
                case "HEALTHY"             -> HEALTHY;
                case "GOVERNANCE_MISSING"  -> GOVERNANCE_MISSING;
                case "POLICY_MISSING"      -> POLICY_MISSING;
                case "OVERPROVISIONED"     -> OVERPROVISIONED;
                case "UNDERUTILIZED"       -> UNDERUTILIZED;
                case "ORPHANED"            -> ORPHANED;
                case "NEW_RESOURCE"        -> NEW_RESOURCE;
                case "SECURITY_RISK"       -> SECURITY_RISK;
                case "COST_OPTIMIZATION"   -> COST_OPTIMIZATION;
                // Tolerant aliases produced by Gemini
                case "POTENTIAL_WASTE",
                     "WASTE",
                     "COST_REDUCTION",
                     "RIGHTSIZING",
                     "DOWNTIER"            -> COST_OPTIMIZATION;
                default                    -> COST_OPTIMIZATION;
            };
        }

        @JsonValue
        public String toJson() {
            return name();
        }
    }

    public enum Severity {
        NONE,
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL;

        @JsonCreator
        public static Severity fromString(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            String upper = value.trim().toUpperCase(Locale.ROOT);
            return switch (upper) {
                case "NONE"     -> NONE;
                case "LOW"      -> LOW;
                case "MEDIUM"   -> MEDIUM;
                case "HIGH"     -> HIGH;
                case "CRITICAL" -> CRITICAL;
                default         -> NONE;
            };
        }

        @JsonValue
        public String toJson() {
            return name();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // RemediationScripts – structured deployment scripts with RBAC roles
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Structured collection of remediation scripts in all supported IaC languages
     * plus the minimal Azure RBAC roles required to execute them.
     *
     * <p>Each script field may be {@code null} when the language is not applicable
     * for the specific remediation. The frontend renders only non-null languages
     * as active tabs.
     */
    public record RemediationScripts(
            String description,
            String azureCli,
            String terraform,
            String azurePowerShell,
            String bicep,
            List<String> requiredRbacRoles) {

        public static final RemediationScripts EMPTY = new RemediationScripts(
                "", null, null, null, null, List.of());

        public RemediationScripts {
            if (description == null) description = "";
            if (requiredRbacRoles == null) requiredRbacRoles = List.of();
        }

        /**
         * Returns {@code true} when at least one IaC script is present.
         */
        public boolean hasAnyScript() {
            return azureCli != null || terraform != null
                   || azurePowerShell != null || bicep != null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Convenience factories
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Creates a "healthy resource" report – no issues, no code, no savings.
     */
    public static AnalysisReportDto healthy(String summary) {
        return new AnalysisReportDto(
                summary,
                false,
                IssueType.HEALTHY,
                0.0,
                Severity.NONE,
                RemediationScripts.EMPTY,
                List.of("Continue monitoring via Azure Advisor."));
    }

    /**
     * Creates a fallback report when the AI is unavailable (network error,
     * parse error, missing key, etc.).
     */
    public static AnalysisReportDto fallback(String reason) {
        return new AnalysisReportDto(
                "AI analysis unavailable – " + reason,
                false,
                IssueType.HEALTHY,
                0.0,
                Severity.NONE,
                RemediationScripts.EMPTY,
                List.of("Check your Gemini API key and network connection.",
                        "Ensure the credentials in /api/v1/config are correct."));
    }
}
