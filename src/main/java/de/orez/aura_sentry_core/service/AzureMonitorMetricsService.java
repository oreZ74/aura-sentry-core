package de.orez.aura_sentry_core.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.orez.aura_sentry_core.persistence.entity.CloudCredentialsEntity;
import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import de.orez.aura_sentry_core.persistence.repository.CloudCredentialsRepository;
import de.orez.aura_sentry_core.persistence.repository.UserRepository;

/**
 * Service that fetches Azure Monitor metrics for Azure resources.
 *
 * <p>Uses the Azure Monitor Metrics REST API (not the SDK) to stay consistent
 * with the {@link AzureCostService} architecture and avoid additional Maven
 * dependencies.
 *
 * <p><b>Error handling strategy:</b> every public method is wrapped in a
 * try-catch that returns an empty {@code Map} on <i>any</i> failure.
 * A metric fetch must never crash the resource scan.
 *
 * <p>Required RBAC role on the service principal:
 * {@code Monitoring Reader} (or {@code Reader} which includes it).
 */
@Service
public class AzureMonitorMetricsService {

    private static final Logger log = LoggerFactory.getLogger(AzureMonitorMetricsService.class);

    private static final String METRICS_API_URL_PATTERN =
            "https://management.azure.com%s/providers/Microsoft.Insights/metrics"
                    + "?api-version=2023-10-01"
                    + "&metricnames=UsedCapacity,Transactions"
                    + "&timespan=%s"
                    + "&aggregation=Average,Total"
                    + "&interval=PT1H";

    private static final String PLACEHOLDER_UUID = "00000000-0000-0000-0000-000000000000";
    private static final String PLACEHOLDER_TEXT = "configure-via-dashboard";

    private final CloudCredentialsRepository credentialsRepository;
    private final CredentialEncryptionService encryptionService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public AzureMonitorMetricsService(
            CloudCredentialsRepository credentialsRepository,
            CredentialEncryptionService encryptionService,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.credentialsRepository = credentialsRepository;
        this.encryptionService = encryptionService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches {@code UsedCapacity} (bytes) and {@code Transactions} (7-day total)
     * for a single Storage Account.
     *
     * <p><b>Hard-FinOps Garantie:</b> Diese Methode liefert niemals eine leere
     * Map. Fehlende Metriken (API-Fehler, leere timeseries, unbekannte Keys)
     * werden als echte {@code 0.0} interpretiert, damit die Regeln eine
     * harte Entscheidung treffen können statt zu "raten".
     *
     * @param resourceId full ARM resource ID, e.g.
     *                   {@code /subscriptions/.../storageAccounts/myacct}
     * @return map with keys {@code "UsedCapacity"} and {@code "Transactions"}.
     *         Never returns an empty map; missing values are hard-defaulted to {@code 0.0}.
     */
    public Map<String, Double> fetchStorageMetrics(String resourceId) {
        try {
            ResolvedCredentials creds = resolveCredentials();
            String token = acquireToken(creds);
            return queryStorageMetrics(token, resourceId);

        } catch (IllegalStateException e) {
            log.error("[MetricsService] CREDENTIAL ERROR for '{}': {}", resourceId, e.getMessage());
            return defaultMetrics();
        } catch (RestClientResponseException e) {
            // ── Azure API hat uns einen Fehler zurückgeworfen ──
            // Der Response Body enthält die echte Ursache (AuthorizationFailed,
            // InvalidResourceId, NoMetricsData, etc.).  Ohne diesen Body sind wir blind.
            String responseBody = e.getResponseBodyAsString();
            log.error("[MetricsService] AZURE API REJECTED the metrics request for '{}'\n"
                            + "    HTTP Status : {}\n"
                            + "    Headers     : {}\n"
                            + "    Response Body : {}\n"
                            + "    → Fix: Check RBAC role (Monitoring Reader) and resource ID correctness.",
                    resourceId, e.getStatusCode().value(), e.getResponseHeaders(), responseBody);
            return defaultMetrics();
        } catch (Exception e) {
            log.error("[MetricsService] UNEXPECTED FAILURE for '{}'\n"
                            + "    Exception : {}\n"
                            + "    Message   : {}\n"
                            + "    → Stacktrace folgt im nächsten log.error()",
                    resourceId, e.getClass().getName(), e.getMessage(), e);
            return defaultMetrics();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Azure Monitor Metrics REST API
    // ─────────────────────────────────────────────────────────────────────────────

    private Map<String, Double> queryStorageMetrics(String token, String resourceId) {
        Instant now = Instant.now();
        Instant sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);
        String timespan = sevenDaysAgo + "/" + now;

        String url = METRICS_API_URL_PATTERN.formatted(resourceId, timespan);

        log.debug("[MetricsService] Querying metrics for '{}', timespan={}", resourceId, timespan);

        String responseBody = RestClient.create()
                .get()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .retrieve()
                .body(String.class);

        if (responseBody == null || responseBody.isBlank()) {
            log.debug("[MetricsService] Empty response for '{}'", resourceId);
            return defaultMetrics();
        }

        return parseMetrics(responseBody, resourceId);
    }

    private Map<String, Double> parseMetrics(String json, String resourceId) {
        // Hard-FinOps Garantie: Jeder Storage Account bekommt beide Metrik-Keys.
        // Fehlende Azure-Daten (leere timeseries, nicht vorhandene Metriken,
        // JSON-Fehler) werden als echte 0.0 interpretiert. null/empty-Maps
        // sind ausgeschlossen — die Rule muss eine harte Entscheidung treffen.
        Map<String, Double> metrics = new HashMap<>(Map.of(
                "UsedCapacity", 0.0,
                "Transactions", 0.0));

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode valueArray = root.path("value");

            if (!valueArray.isArray()) {
                log.debug("[MetricsService] Unexpected JSON structure for '{}'", resourceId);
                return Map.copyOf(metrics); // Defaults (0.0, 0.0)
            }

            for (JsonNode metric : valueArray) {
                String metricName = metric.path("name").path("value").asText("");
                JsonNode timeSeriesArray = metric.path("timeseries");

                switch (metricName) {
                    case "UsedCapacity" -> {
                        Double capacity = extractLastAverage(timeSeriesArray);
                        // Auch bei leerer timeseries bleibt der Default 0.0 stehen
                        if (capacity != null) {
                            metrics.put("UsedCapacity", capacity);
                            log.debug("[MetricsService] '{}' UsedCapacity = {} bytes", resourceId, capacity);
                        }
                    }
                    case "Transactions" -> {
                        Double txSum = extractTotalSum(timeSeriesArray);
                        if (txSum != null) {
                            metrics.put("Transactions", txSum);
                            log.debug("[MetricsService] '{}' Transactions (7d) = {}", resourceId, txSum);
                        }
                    }
                    default -> log.trace("[MetricsService] Ignoring metric '{}' for '{}'", metricName, resourceId);
                }
            }

        } catch (Exception e) {
            log.warn("[MetricsService] JSON parse error for '{}': {}", resourceId, e.getMessage());
        }

        return Map.copyOf(metrics);
    }

    /**
     * Hard-default metric map. Wird verwendet, wenn die Azure API komplett
     * fehlschlägt (Auth, Netzwerk, RBAC). Die Rule interpretiert 0.0 als
     * echte Null-Nutzung → ORPHANED / UNDERUTILIZED.
     */
    private static Map<String, Double> defaultMetrics() {
        return Map.of(
                "UsedCapacity", 0.0,
                "Transactions", 0.0);
    }

    /**
     * For capacity metrics (UsedCapacity) we take the <em>last</em> average value.
     * Capacity is a point-in-time gauge, not a rate.
     */
    private Double extractLastAverage(JsonNode timeSeriesArray) {
        for (JsonNode series : timeSeriesArray) {
            JsonNode data = series.path("data");
            if (!data.isArray() || data.isEmpty()) {
                continue;
            }
            // Take the last data point — most recent capacity reading
            JsonNode lastPoint = data.get(data.size() - 1);
            if (lastPoint.has("average")) {
                return lastPoint.path("average").asDouble(Double.NaN);
            }
        }
        return null;
    }

    /**
     * For transaction metrics we <em>sum</em> all {@code total} values across
     * the 7-day window to get the complete transaction count.
     */
    private Double extractTotalSum(JsonNode timeSeriesArray) {
        double sum = 0.0;
        boolean found = false;

        for (JsonNode series : timeSeriesArray) {
            JsonNode data = series.path("data");
            if (!data.isArray()) {
                continue;
            }
            for (JsonNode point : data) {
                if (point.has("total")) {
                    double val = point.path("total").asDouble(0.0);
                    sum += val;
                    found = true;
                }
            }
        }

        return found ? sum : null;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Credential helpers (mirrors AzureCostService for consistency)
    // ─────────────────────────────────────────────────────────────────────────────

    private record ResolvedCredentials(
            String tenantId, String clientId, String clientSecret, String subscriptionId) {
    }

    private ResolvedCredentials resolveCredentials() {
        UserEntity currentUser = userRepository.findByUsername(
                        SecurityContextHolder.getContext().getAuthentication().getName())
                .orElseThrow(() -> new IllegalStateException("No authenticated user found for metrics query"));

        CloudCredentialsEntity creds = credentialsRepository.findTopByUserOrderByUpdatedAtDesc(currentUser)
                .orElseThrow(() -> new IllegalStateException("No Azure credentials configured for `"
                        + currentUser.getUsername() + "`."));

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

    private String acquireToken(ResolvedCredentials creds) {
        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .tenantId(creds.tenantId())
                .clientId(creds.clientId())
                .clientSecret(creds.clientSecret())
                .build();

        TokenRequestContext context = new TokenRequestContext();
        context.addScopes("https://management.azure.com/.default");

        var token = credential.getToken(context).block();
        if (token == null || token.getToken() == null) {
            throw new IllegalStateException("Failed to acquire Azure access token");
        }
        return token.getToken();
    }

    private String decryptField(String fieldName, String ciphertext) {
        try {
            return encryptionService.decrypt(ciphertext);
        } catch (Exception e) {
            log.error("[MetricsService] Failed to decrypt `{}`", fieldName);
            throw new IllegalStateException("Credential decryption failed for `" + fieldName + "`.", e);
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
