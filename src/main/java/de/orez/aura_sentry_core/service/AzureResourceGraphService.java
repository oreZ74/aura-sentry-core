package de.orez.aura_sentry_core.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.orez.aura_sentry_core.persistence.entity.CloudCredentialsEntity;
import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import de.orez.aura_sentry_core.persistence.repository.CloudCredentialsRepository;
import de.orez.aura_sentry_core.persistence.repository.UserRepository;

@Service
public class AzureResourceGraphService {

    private static final Logger log = LoggerFactory.getLogger(AzureResourceGraphService.class);

    private static final String GRAPH_API_URL =
            "https://management.azure.com/providers/Microsoft.ResourceGraph/resources?api-version=2021-03-01";

    private static final String PLACEHOLDER_UUID = "00000000-0000-0000-0000-000000000000";
    private static final String PLACEHOLDER_TEXT = "configure-via-dashboard";

    private final CloudCredentialsRepository credentialsRepository;
    private final CredentialEncryptionService encryptionService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public AzureResourceGraphService(
            CloudCredentialsRepository credentialsRepository,
            CredentialEncryptionService encryptionService,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.credentialsRepository = credentialsRepository;
        this.encryptionService = encryptionService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Instant> fetchCreationTimes() {
        try {
            ResolvedCredentials creds = resolveCredentials();
            String token = acquireToken(creds);
            return queryResourceGraph(token, creds.subscriptionId());

        } catch (IllegalStateException e) {
            log.warn("[ResourceGraph] Credential error: {}", e.getMessage());
            return Map.of();
        } catch (RestClientResponseException e) {
            log.warn("[ResourceGraph] API HTTP {} -- may need Reader RBAC role on subscription",
                    e.getStatusCode().value());
            return Map.of();
        } catch (Exception e) {
            log.warn("[ResourceGraph] Failed to fetch creation times: {} -- {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return Map.of();
        }
    }

    /**
     * Fetches tags for all subscription resources via Resource Graph.
     *
     * <p>This is a reliable fallback because the ARM
     * {@code genericResources().list()} API often omits the {@code tags}
     * field for non-standard resource types (e.g.
     * {@code microsoft.alertsmanagement/smartdetectoralertrules}).
     * Resource Graph always includes tags.
     *
     * @return map of lowercased resource ID → tags map; empty map on error
     */
    public Map<String, Map<String, String>> fetchTags() {
        try {
            ResolvedCredentials creds = resolveCredentials();
            String token = acquireToken(creds);
            return queryGraphForTags(token, creds.subscriptionId());

        } catch (IllegalStateException e) {
            log.warn("[ResourceGraph] Credential error for tags: {}", e.getMessage());
            return Map.of();
        } catch (RestClientResponseException e) {
            log.warn("[ResourceGraph] Tags query HTTP {}", e.getStatusCode().value());
            return Map.of();
        } catch (Exception e) {
            log.warn("[ResourceGraph] Failed to fetch tags: {} -- {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Map<String, String>> queryGraphForTags(String token, String subscriptionId) {
        String kql = "resources | project id, tags";

        String body = """
                {
                  "subscriptions": ["%s"],
                  "query": "%s",
                  "options": { "resultFormat": "table" }
                }""".formatted(subscriptionId, kql);

        RestClient client = RestClient.builder().build();

        String responseJson = client.post()
                .uri(GRAPH_API_URL)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);

        return parseTagsResponse(responseJson);
    }

    /**
     * Parses the tags from a Resource Graph table response.
     *
     * <p>Resource Graph returns {@code dynamic} fields as JSON strings
     * (e.g. {@code "{\\"Environment\\":\\"Dev\\"}"}). This method
     * deserialises each row's tags value into a {@code Map<String, String>}.
     */
    private Map<String, Map<String, String>> parseTagsResponse(String json) {
        Map<String, Map<String, String>> result = new HashMap<>();

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            JsonNode rows = data.path("rows");

            if (rows.isMissingNode() || !rows.isArray()) {
                return result;
            }

            for (JsonNode row : rows) {
                if (!row.isArray() || row.size() < 2) {
                    continue;
                }

                String resourceId = row.get(0).asText("").toLowerCase();
                JsonNode tagsNode = row.get(1);

                if (resourceId.isBlank() || tagsNode == null || tagsNode.isNull()) {
                    continue;
                }

                try {
                    // Resource Graph returns dynamic as a JSON-encoded string
                    Map<String, String> tags;
                    if (tagsNode.isTextual()) {
                        tags = objectMapper.readValue(tagsNode.asText(),
                                new TypeReference<Map<String, String>>() {});
                    } else {
                        tags = objectMapper.convertValue(tagsNode,
                                new TypeReference<Map<String, String>>() {});
                    }
                    if (!tags.isEmpty()) {
                        result.put(resourceId, tags);
                    }
                } catch (Exception e) {
                    log.debug("[ResourceGraph] Failed to parse tags for '{}': {}", resourceId, e.getMessage());
                }
            }

            log.info("[ResourceGraph] Fetched tags for {} resources (subscription {})",
                    result.size(), "configured");
        } catch (Exception e) {
            log.debug("[ResourceGraph] Failed to parse tags response: {}", e.getMessage());
        }

        return result;
    }

    private Map<String, Instant> queryResourceGraph(String token, String subscriptionId) {
        String kql = "resources | project id, createdTime = properties.creationTime | where isnotempty(createdTime)";

        String body = """
                {
                  "subscriptions": ["%s"],
                  "query": "%s",
                  "options": { "resultFormat": "table" }
                }""".formatted(subscriptionId, kql);

        RestClient client = RestClient.builder().build();

        String responseJson = client.post()
                .uri(GRAPH_API_URL)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);

        return parseGraphResponse(responseJson);
    }

    private Map<String, Instant> parseGraphResponse(String json) {
        Map<String, Instant> result = new HashMap<>();

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            JsonNode rows = data.path("rows");

            if (rows.isMissingNode() || !rows.isArray()) {
                return result;
            }

            for (JsonNode row : rows) {
                if (!row.isArray() || row.size() < 2) {
                    continue;
                }

                String resourceId = row.get(0).asText("").toLowerCase();
                String createdTimeStr = row.get(1).asText("").trim();

                if (resourceId.isBlank() || createdTimeStr.isBlank()) {
                    continue;
                }

                try {
                    Instant createdTime = DateTimeFormatter.ISO_INSTANT.parse(createdTimeStr, Instant::from);
                    result.put(resourceId, createdTime);
                } catch (Exception e) {
                    log.debug("[ResourceGraph] Failed to parse creationTime `{}` for `{}`: {}",
                            createdTimeStr, resourceId, e.getMessage());
                }
            }

            log.info("[ResourceGraph] Fetched creation times for {} resources (subscription {})",
                    result.size(), "configured");
        } catch (Exception e) {
            log.debug("[ResourceGraph] Failed to parse response: {}", e.getMessage());
        }

        return result;
    }

    public Instant resolveCreatedAt(String resourceId, Map<String, Instant> creationTimeCache) {
        if (creationTimeCache == null || resourceId == null) {
            return Instant.now();
        }
        return creationTimeCache.getOrDefault(resourceId.toLowerCase(), Instant.now());
    }

    private record ResolvedCredentials(
            String tenantId, String clientId, String clientSecret, String subscriptionId) {}

    private ResolvedCredentials resolveCredentials() {
        UserEntity currentUser = userRepository.findByUsername(
                        SecurityContextHolder.getContext().getAuthentication().getName())
                .orElseThrow(() -> new IllegalStateException("No authenticated user found for Resource Graph query"));

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
            log.error("[ResourceGraph] Failed to decrypt `{}`", fieldName);
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