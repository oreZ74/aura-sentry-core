package de.orez.aura_sentry_core.controller.dto;

import java.time.Instant;
import java.util.List;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.model.OptimizationResult;

public record AnalysisResponse(
        Instant analyzedAt,
        int totalResources,
        AnalysisSummary summary,
        List<OptimizationResult> results) {
    public record AnalysisSummary(long healthy, long orphaned, long potentialWaste) {

        public static AnalysisSummary from(List<OptimizationResult> results) {
            long healthy = results.stream()
                    .flatMap(r -> r.findings().stream())
                    .filter(f -> f.flag() == IssueType.HEALTHY)
                    .count();
            return new AnalysisSummary(healthy,
                    results.stream().flatMap(r -> r.findings().stream()).count() - healthy, 0);
        }
    }
}
