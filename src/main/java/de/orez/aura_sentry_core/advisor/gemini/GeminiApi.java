package de.orez.aura_sentry_core.advisor.gemini;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request and response types for the Google Gemini generateContent API.
 * Gemini API Reference: https://ai.google.dev/api/generate-content
 */
public final class GeminiApi {

    private GeminiApi() {
    }

    // {"contents": [{"parts": [{"text": "..."}]}], "generationConfig": {"temperature": 0.0}}
    public record GenerateContentRequest(
            List<Content> contents,
            @JsonInclude(JsonInclude.Include.NON_NULL) GenerationConfig generationConfig) {

        public record Content(List<Part> parts) {
        }

        public record Part(String text) {
        }

        public record GenerationConfig(double temperature, Integer topK, Double topP) {}

        private static final GenerationConfig DETERMINISTIC =
                new GenerationConfig(0.0, 1, 1.0);

        /** Convenience factory method for a single text prompt with deterministic settings. */
        public static GenerateContentRequest of(String prompt) {
            return new GenerateContentRequest(
                    List.of(new Content(List.of(new Part(prompt)))),
                    DETERMINISTIC);
        }
    }

    public record GenerateContentResponse(List<Candidate> candidates) {

        public record Candidate(Content content) {

            public record Content(List<Part> parts) {
            }

            public record Part(String text) {
            }
        }

        /** Returns the text of the first candidate, or an empty string if absent. */
        public String firstText() {
            if (candidates == null || candidates.isEmpty()) {
                return "";
            }
            var parts = candidates.get(0).content().parts();
            if (parts == null || parts.isEmpty()) {
                return "";
            }
            return parts.get(0).text() != null ? parts.get(0).text() : "";
        }
    }
}
