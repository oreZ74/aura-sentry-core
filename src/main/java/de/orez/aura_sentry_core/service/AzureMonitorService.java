package de.orez.aura_sentry_core.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.orez.aura_sentry_core.model.ResourceMetrics;
import de.orez.aura_sentry_core.persistence.entity.CloudCredentialsEntity;
import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import de.orez.aura_sentry_core.persistence.repository.CloudCredentialsRepository;
import de.orez.aura_sentry_core.persistence.repository.UserRepository;

/**
 * Queries Azure Monitor Metrics API for per-resource telemetry data.
 *
 * <p>Uses the Azure Monitor REST API directly (no additional SDK dependencies)
 * to query CPU and network metrics for a given resource over a 7-day window.
 *
 * <h3>Metrics queried</h3>
 * <ul>
 *   <li>{@code Percentage CPU} — 7-day average (Avg aggregation)</li>
 *   <li>{@code Network In Total} — 7-day sum (Total aggregation)</li>
 *   <li>{@code Network Out Total} — 7-day sum (Total aggregation)</li>
 * </ul>
 *
 * <h3>Graceful degradation</h3>
 * If a resource type does not expose CPU or network metrics (e.g. Public IPs,
 * Storage Accounts), the REST call returns empty data or a 400/404 status.
 * This service catches all exceptions and returns {@link ResourceMetrics#empty()}
 * so the scan pipeline is never blocked.
 */
@Service
public class AzureMonitorService {

    private static final Logger log = LoggerFactory.getLogger(AzureMonitorService.class);

    private static final Duration OBSERVATION_WINDOW = Duration.ofDays(7);

    private static final String PLACEHOLDER_UUID = "00000000-0000-0000-0000-000000000000";
    private static final String PLACEHOLDER_TEXT = "configure-via-dashboard";

    private static final String MONITOR_BASE = "https://management.azure.com";
    private static final String API_VERSION = "2024-02-01";

    private static final String METRIC_CPU = "Percentage CPU";
    private static final String METRIC_NETWORK_IN = "Network In Total";
    private static final String METRIC_NETWORK_OUT = "Network Out Total";
    private static final String METRIC_STORAGE_USED_CAPACITY = "UsedCapacity";

    private final CloudCredentialsRepository credentialsRepository;
    private final CredentialEncryptionService encryptionService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AzureMonitorService(
            CloudCredentialsRepository credentialsRepository,
            CredentialEncryptionService encryptionService,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.credentialsRepository = credentialsRepository;
        this.encryptionService = encryptionService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Queries Azure Monitor for the given resource's metrics over the last
     * {@value #OBSERVATION_WINDOW} days.
     *
     * @param resourceId the full Azure resource ID
     * @return metrics record, or {@link ResourceMetrics#empty()} on failure
     */
    public ResourceMetrics fetchMetrics(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            log.debug("[Monitor] No resourceId provided → returning empty metrics");
            return ResourceMetrics.empty();
        }

        try {
            ResolvedCredentials creds = resolveCredentials();
            String token = acquireToken(creds);

            double avgCpu = queryMetric(token, resourceId, METRIC_CPU, "Avg");
            double netIn = queryMetric(token, resourceId, METRIC_NETWORK_IN, "Total");
            double netOut = queryMetric(token, resourceId, METRIC_NETWORK_OUT, "Total");

            log.debug("[Monitor] Metrics for '{}': CPU={}%, NetIn={}B, NetOut={}B",
                    resourceId, avgCpu, netIn, netOut);

            return ResourceMetrics.of(avgCpu, netIn, netOut);

        } catch (IllegalStateException e) {
            log.warn("[Monitor] Credential error for '{}': {}", resourceId, e.getMessage());
            return ResourceMetrics.empty();
        } catch (Exception e) {
            log.warn("[Monitor] Failed to fetch metrics for '{}': {}", resourceId, e.getMessage());
            return ResourceMetrics.empty();
        }
    }

    /**
     * Queries the Azure Monitor {@code UsedCapacity} metric for a storage
     * account, returning the most recent value in GiB.
     *
     * <p>The {@code UsedCapacity} metric is the authoritative source for
     * actual blob usage – drastically more accurate than hardcoded constants
     * like {@code TYPICAL_STORAGE_USAGE_GB = 100.0}.
     *
     * <p>Uses the {@code Average} aggregation over the last 24 hours for a
     * stable reading that smooths out momentary spikes.
     *
     * @param resourceId full Azure resource ID of the storage account
     * @return used capacity in GiB, or {@code NaN} if the metric is unavailable
     *         (resource type doesn't support it, credentials wrong, etc.)
     */
    public double fetchStorageUsedCapacity(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return Double.NaN;
        }

        try {
            ResolvedCredentials creds = resolveCredentials();
            String token = acquireToken(creds);

            double usedBytes = queryMetric(token, resourceId, METRIC_STORAGE_USED_CAPACITY, "Avg");
            if (usedBytes <= 0.0) {
                log.debug("[Monitor] UsedCapacity returned 0 for '{}'", resourceId);
                return Double.NaN;
            }

            double usedGb = usedBytes / (1024.0 * 1024.0 * 1024.0);
            log.info("[Monitor] Storage '{}': {} GiB used (UsedCapacity metric)", resourceId, usedGb);
            return usedGb;

        } catch (IllegalStateException e) {
            log.warn("[Monitor] Credential error for storage metric '{}': {}", resourceId, e.getMessage());
            return Double.NaN;
        } catch (Exception e) {
            log.warn("[Monitor] Failed to fetch UsedCapacity for '{}': {}", resourceId, e.getMessage());
            return Double.NaN;
        }
    }

    /**
     * Queries metrics for multiple resources in parallel using virtual threads.
     */
    public List<ResourceMetrics> fetchMetricsBatch(List<String> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return List.of();
        }

        ResolvedCredentials creds = resolveCredentials();
        String token;
        try {
            token = acquireToken(creds);
        } catch (Exception e) {
            log.warn("[Monitor] Failed to acquire token for batch query: {}", e.getMessage());
            return resourceIds.stream().map(r -> ResourceMetrics.empty()).toList();
        }

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = resourceIds.stream()
                    .map(id -> executor.submit(() -> {
                        double cpu = queryMetric(token, id, METRIC_CPU, "Avg");
                        double netIn = queryMetric(token, id, METRIC_NETWORK_IN, "Total");
                        double netOut = queryMetric(token, id, METRIC_NETWORK_OUT, "Total");
                        return ResourceMetrics.of(cpu, netIn, netOut);
                    }))
                    .toList();

            return futures.stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            log.debug("[Monitor] Batch metric fetch failed: {}", e.getMessage());
                            return ResourceMetrics.empty();
                        }
                    })
                    .toList();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Azure Monitor REST API query
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Queries a single metric from the Azure Monitor REST API.
     *
     * <p>Endpoint: {@code GET /subscriptions/{sub}/providers/Microsoft.Insights/metrics}
     * with query parameters for metric name, aggregation, and time range.
     */
    private double queryMetric(String token, String resourceId, String metricName, String aggregation) {
        Instant now = Instant.now();
        Instant start = now.minus(OBSERVATION_WINDOW);

        String startStr = DateTimeFormatter.ISO_INSTANT.format(start);
        String endStr = DateTimeFormatter.ISO_INSTANT.format(now);

        // Build the metric query URI
        // Resource ID needs to be URL-encoded
        String encodedResourceId = resourceId.replace(" ", "%20");

        String uri = MONITOR_BASE
                + "/subscriptions/" + extractSubscriptionId(resourceId)
                + "/providers/Microsoft.Insights/metrics"
                + "?metricNames=" + metricName.replace(" ", "%20")
                + "&aggregation=" + aggregation
                + "&timespan=" + startStr + "/" + endStr
                + "&resourceId=" + encodedResourceId
                + "&api-version=" + API_VERSION;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.debug("[Monitor] Metric query returned HTTP {} for '{}' ({})",
                        response.statusCode(), metricName, resourceId);
                return 0.0;
            }

            return parseMetricResponse(response.body(), aggregation);

        } catch (Exception e) {
            log.debug("[Monitor] Failed to query metric '{}' for '{}': {}",
                    metricName, resourceId, e.getMessage());
            return 0.0;
        }
    }

    /**
     * Parses the Azure Monitor metrics JSON response and extracts the
     * aggregated value.
     *
     * <p>Response structure:
     * <pre>
     * {
     *   "value": [{
     *     "name": { "value": "Percentage CPU", "localizedValue": "..." },
     *     "timeseries": [{
     *       "data": [
     *         { "average": 2.3, "total": null, "count": 1, "start": "..." },
     *         ...
     *       ]
     *     }]
     *   }]
     * }
     * </pre>
     */
    private double parseMetricResponse(String json, String aggregation) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode values = root.path("value");

            if (values.isMissingNode() || !values.isArray() || values.isEmpty()) {
                return 0.0;
            }

            JsonNode firstMetric = values.get(0);
            JsonNode timeseries = firstMetric.path("timeseries");

            if (timeseries.isMissingNode() || !timeseries.isArray() || timeseries.isEmpty()) {
                return 0.0;
            }

            JsonNode dataPoints = timeseries.get(0).path("data");
            if (dataPoints.isMissingNode() || !dataPoints.isArray()) {
                return 0.0;
            }

            // Sum up all data points for the aggregation type
            double sum = 0.0;
            for (JsonNode point : dataPoints) {
                JsonNode field = "Total".equals(aggregation) ? point.path("total") : point.path("average");
                if (!field.isMissingNode() && !field.isNull()) {
                    sum += field.asDouble();
                }
            }

            // For "Average" aggregation, compute the mean across data points
            if ("Avg".equals(aggregation) && !dataPoints.isEmpty()) {
                return sum / dataPoints.size();
            }

            return sum;

        } catch (Exception e) {
            log.debug("[Monitor] Failed to parse metric response: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * Extracts the subscription ID from a full Azure resource ID.
     * Input: "/subscriptions/xxx-xxx/resourceGroups/rg/providers/..."
     * Output: "xxx-xxx"
     */
    private String extractSubscriptionId(String resourceId) {
        if (resourceId == null) return "";
        String[] parts = resourceId.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("subscriptions".equalsIgnoreCase(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Credential resolution + token acquisition
    // ─────────────────────────────────────────────────────────────────────────────

    private record ResolvedCredentials(
            String tenantId, String clientId, String clientSecret, String subscriptionId) {}

    private ResolvedCredentials resolveCredentials() {
        UserEntity currentUser = userRepository.findByUsername(
                        SecurityContextHolder.getContext().getAuthentication().getName())
                .orElseThrow(() -> new IllegalStateException("No authenticated user found for monitor query"));

        CloudCredentialsEntity creds = credentialsRepository.findTopByUserOrderByUpdatedAtDesc(currentUser)
                .orElseThrow(() -> new IllegalStateException("No Azure credentials configured for '"
                        + currentUser.getUsername() + "'."));

        String tenantId = decryptField("tenant_id", creds.getEncryptedTenantId());
        String clientId = decryptField("client_id", creds.getEncryptedClientId());
        String clientSecret = decryptField("client_secret", creds.getEncryptedClientSecret());
        String subscriptionId = decryptField("subscription_id", creds.getEncryptedSubscriptionId());

        if (isPlaceholder(tenantId) || isPlaceholder(clientId)
                || isPlaceholder(clientSecret) || isPlaceholder(subscriptionId)) {
            throw new IllegalStateException("Azure credentials are placeholder values.");
        }

        return new ResolvedCredentials(tenantId, clientId, clientSecret, subscriptionId);
    }

    /**
     * Acquires an OAuth2 access token for Azure Resource Manager using
     * the client credentials flow.
     */
    private String acquireToken(ResolvedCredentials creds) throws Exception {
        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .tenantId(creds.tenantId())
                .clientId(creds.clientId())
                .clientSecret(creds.clientSecret())
                .build();

        TokenRequestContext context = new TokenRequestContext();
        context.addScopes("https://management.azure.com/.default");

        AccessToken token = credential.getToken(context).block();
        if (token == null || token.getToken() == null) {
            throw new IllegalStateException("Failed to acquire Azure access token");
        }
        return token.getToken();
    }

    private String decryptField(String fieldName, String ciphertext) {
        try {
            return encryptionService.decrypt(ciphertext);
        } catch (Exception e) {
            log.error("[Monitor] Failed to decrypt '{}'", fieldName);
            throw new IllegalStateException("Credential decryption failed for '" + fieldName + "'.", e);
        }
    }

    private boolean isPlaceholder(String value) {
        if (value == null) return true;
        String trimmed = value.trim();
        return trimmed.isEmpty()
                || trimmed.equalsIgnoreCase(PLACEHOLDER_UUID)
                || trimmed.equalsIgnoreCase(PLACEHOLDER_TEXT);
    }
}
