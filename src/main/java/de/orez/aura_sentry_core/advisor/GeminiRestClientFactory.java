package de.orez.aura_sentry_core.advisor;

import java.net.http.HttpClient;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Factory for a shared {@link RestClient} targeting the Google Gemini
 * generateContent API.
 *
 * <p>The underlying {@link java.net.http.HttpClient} is a singleton as
 * recommended by the JDK documentation.  A single {@link RestClient}
 * instance is reused for all requests — thread-safe by design.
 */
@Component
public class GeminiRestClientFactory {

    private static final Logger log = LoggerFactory.getLogger(GeminiRestClientFactory.class);

    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final RestClient restClient;

    public GeminiRestClientFactory() {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);

        this.restClient = RestClient.builder()
                .baseUrl(GEMINI_BASE_URL)
                .requestFactory(factory)
                .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("GeminiRestClient initialized (base={}, connectTimeout={}, readTimeout={})",
                GEMINI_BASE_URL, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    /**
     * Returns the shared, thread-safe {@link RestClient} instance.
     */
    public RestClient getRestClient() {
        return restClient;
    }
}
