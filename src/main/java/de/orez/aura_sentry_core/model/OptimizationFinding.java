package de.orez.aura_sentry_core.model;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;

public record OptimizationFinding(IssueType flag, String reason) {
}
