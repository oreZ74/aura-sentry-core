package de.orez.aura_sentry_core.model;

import java.time.Instant;

/**
 * Encapsulates Azure Monitor telemetry data for a single resource.
 *
 * <p>Queried via {@link de.orez.aura_sentry_core.service.AzureMonitorService}
 * from the Azure Monitor Metrics API over a 7-day window.
 *
 * <p>Used by the AI Advisor to provide evidence-based optimization
 * recommendations instead of relying solely on static tags or SKU pricing.
 *
 * @param averageCpuUtilization  7-day average CPU utilization (0.0 – 100.0).
 *                               0.0 for resource types that do not expose
 *                               CPU metrics (e.g. Public IPs, Storage Accounts).
 * @param networkInBytes         Total ingress bytes over the 7-day window.
 * @param networkOutBytes        Total egress bytes over the 7-day window.
 * @param isUnderutilized        {@code true} if average CPU < 5 % over 7 days
 *                               — used as a strong signal for right-sizing.
 * @param metricTimestamp        Timestamp of the metric query (audit trail).
 */
public record ResourceMetrics(
        double averageCpuUtilization,
        double networkInBytes,
        double networkOutBytes,
        boolean isUnderutilized,
        Instant metricTimestamp) {

    /** Default threshold for underutilization (5 % CPU). */
    private static final double UNDERUTILIZATION_THRESHOLD = 5.0;

    /**
     * Creates an empty metrics record for resources that do not
     * support CPU or network metrics (e.g. Public IPs, Disks).
     */
    public static ResourceMetrics empty() {
        return new ResourceMetrics(0.0, 0.0, 0.0, false, Instant.now());
    }

    /**
     * Factory method that computes the underutilization flag from
     * the raw CPU average.
     */
    public static ResourceMetrics of(double avgCpu, double netIn, double netOut) {
        return new ResourceMetrics(
                avgCpu,
                netIn,
                netOut,
                avgCpu < UNDERUTILIZATION_THRESHOLD && avgCpu > 0.0,
                Instant.now());
    }
}
