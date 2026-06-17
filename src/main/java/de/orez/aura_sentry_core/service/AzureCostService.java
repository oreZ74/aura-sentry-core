package de.orez.aura_sentry_core.service;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AzureCostService {

    private static final Logger log = LoggerFactory.getLogger(AzureCostService.class);

    private static final String COST_API_URL_PATTERN =
            "https://management.azure.com/subscriptions/%s/providers/Microsoft.CostManagement/query?api-version=2023-11-01";

    private final CredentialResolutionService credentialResolutionService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AzureCostService(CredentialResolutionService credentialResolutionService,
                            ObjectMapper objectMapper) {
        this.credentialResolutionService = credentialResolutionService;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().build();
    }

    @Cacheable(value = "azureCostMapCache", key = "#root.methodName")
    @Retryable(retryFor = RestClientResponseException.class, maxAttempts = 3,
               backoff = @Backoff(delay = 2000))
    public Map<String, Double> fetchCostsByResource() {
        try {
            var creds = credentialResolutionService.resolveAndValidateCredentials();
            String token = acquireToken(creds);
            Map<String, Double> costs = queryCostManagementApi(token, creds.subscriptionId());

            log.info("[CostService] Fetched costs for {} resources (subscription {})",
                    costs.size(), creds.subscriptionId());
            return costs;

        } catch (IllegalStateException e) {
            log.warn("[CostService] Credential error: {}", e.getMessage());
            return Map.of();
        } catch (RestClientResponseException e) {
            log.warn("[CostService] API HTTP {} -- may need Cost Management Reader RBAC role",
                    e.getStatusCode().value());
            return Map.of();
        } catch (Exception e) {
            log.warn("[CostService] Failed to fetch costs: {} -- {}", e.getClass().getSimpleName(), e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Double> queryCostManagementApi(String token, String subscriptionId) {
        String url = COST_API_URL_PATTERN.formatted(subscriptionId);

        String body = """
                {
                  "type": "ActualCost",
                  "timeframe": "MonthToDate",
                  "dataset": {
                    "granularity": "None",
                    "aggregation": {
                      "totalCost": { "name": "PreTaxCost", "function": "Sum" }
                    },
                    "grouping": [
                      { "type": "Dimension", "name": "ResourceId" }
                    ]
                  }
                }""";

        RestClient client = restClient;

        String responseJson = client.post()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);

        return parseCostResponse(responseJson);
    }

    private Map<String, Double> parseCostResponse(String json) {
        Map<String, Double> costMap = new HashMap<>();

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode properties = root.path("properties");
            JsonNode rows = properties.path("rows");

            if (rows.isMissingNode() || !rows.isArray()) {
                return costMap;
            }

            for (JsonNode row : rows) {
                if (!row.isArray() || row.size() < 2) {
                    continue;
                }

                double cost = row.get(0).asDouble(0.0);
                String resourceId = row.get(1).asText("").toLowerCase();

                if (!resourceId.isBlank()) {
                    costMap.put(resourceId, cost);
                }
            }
        } catch (Exception e) {
            log.debug("[CostService] Failed to parse cost response: {}", e.getMessage());
        }

        return costMap;
    }

    private String acquireToken(CredentialResolutionService.ResolvedCredentials creds) {
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
}
