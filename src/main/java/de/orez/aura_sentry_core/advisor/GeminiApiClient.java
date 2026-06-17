package de.orez.aura_sentry_core.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto;
import de.orez.aura_sentry_core.advisor.gemini.GeminiApi;
import de.orez.aura_sentry_core.service.CredentialResolutionService;

/**
 * HTTP transport for the Google Gemini generateContent API.
 *
 * <p>Extracted from {@link RemediationAgent} to separate pure HTTP/JSON
 * concerns (transport, marshalling, code-fence cleaning) from the
 * business orchestration (prompt building, demo-mode detection,
 * parallelism, telemetry enrichment).
 *
 * <p><b>Security:</b> The API key is sent via the {@code x-goog-api-key}
 * HTTP header, never as a URL query parameter.
 */
@Service
public class GeminiApiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiApiClient.class);

    private static final String GEMINI_ENDPOINT_PATTERN = "/v1beta/models/%s:generateContent";

    private final GeminiRestClientFactory restClientFactory;
    private final CredentialResolutionService credentialResolutionService;
    private final ObjectMapper objectMapper;
    private final String modelId;

    public GeminiApiClient(GeminiRestClientFactory restClientFactory,
                           CredentialResolutionService credentialResolutionService,
                           ObjectMapper objectMapper,
                           @org.springframework.beans.factory.annotation.Value("${gemini.model.id:gemini-3.1-flash-lite}") String modelId) {
        this.restClientFactory = restClientFactory;
        this.credentialResolutionService = credentialResolutionService;
        this.modelId = modelId;
        this.objectMapper = objectMapper.copy()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        log.info("GeminiApiClient initialized (model={})", modelId);
    }

    /**
     * Calls the Gemini API with the given prompt and returns a parsed
     * {@link AnalysisReportDto}.
     *
     * @param prompt the full text prompt (system + user + special instructions)
     * @return the AI analysis report
     * @throws IllegalStateException    if the API key is missing or invalid
     * @throws RestClientException      if the HTTP call fails
     */
    @Retryable(retryFor = RestClientException.class, maxAttempts = 3,
               backoff = @Backoff(delay = 2000))
    public AnalysisReportDto callGemini(String prompt) {
        String apiKey = credentialResolutionService.resolveCredentials().geminiApiKey();
        RestClient client = restClientFactory.getRestClient();
        String geminiPath = GEMINI_ENDPOINT_PATTERN.formatted(modelId);

        var request = GeminiApi.GenerateContentRequest.of(prompt);

        String rawJson = client.post()
                .uri(geminiPath)
                .header("x-goog-api-key", apiKey)
                .body(request)
                .retrieve()
                .body(String.class);

        log.debug("Raw Gemini response: {}", rawJson);

        if (rawJson == null || rawJson.isBlank()) {
            throw new RestClientException("Empty response from Gemini API");
        }

        try {
            var response = objectMapper.readValue(rawJson, GeminiApi.GenerateContentResponse.class);
            String innerText = extractResponseText(response);
            String cleaned = cleanJson(innerText);

            return objectMapper.readValue(cleaned, AnalysisReportDto.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Failed to parse Gemini JSON response", e);
            throw new RestClientException("Failed to parse Gemini response: " + e.getMessage());
        }
    }

    private String extractResponseText(GeminiApi.GenerateContentResponse response) {
        if (response.candidates() == null || response.candidates().isEmpty()) {
            return "";
        }
        var parts = response.candidates().get(0).content().parts();
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        String text = parts.get(0).text();
        return text != null ? text : "";
    }

    /**
     * Cleans Gemini output: removes Markdown code fences and trims
     * whitespace that Gemini sometimes wraps around the JSON body.
     */
    static String cleanJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String cleaned = raw.replaceAll("(?s)^```(json)?\\s*", "");
        cleaned = cleaned.replaceAll("(?s)\\s*```$", "");
        return cleaned.trim();
    }
}
