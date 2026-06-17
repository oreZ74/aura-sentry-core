package de.orez.aura_sentry_core.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Executes Azure remediation commands asynchronously via {@link ProcessBuilder}.
 *
 * <p><b>Security design:</b>
 * <ul>
 *   <li>Inputs are validated against strict whitelists <em>before</em> any
 *       process is spawned.</li>
 *   <li>The command is built as a {@code String[]} — no shell string
 *       concatenation, therefore no shell injection (e.g. {@code ; rm -rf /}).</li>
 *   <li>Only {@code az tag update} is permitted; any other command aborts.</li>
 *   <li>Execution runs on a dedicated async thread pool so HTTP requests
 *       never block waiting for the Azure CLI.</li>
 * </ul>
 *
 * <p><b>Prerequisite:</b> The host running this application must have the
 * Azure CLI installed and authenticated ({@code az login}).  If the CLI is
 * not logged in the job fails gracefully and logs the exact error.
 */
@Service
public class RemediationService {

    private static final Logger log = LoggerFactory.getLogger(RemediationService.class);

    // ── Whitelists ───────────────────────────────────────────────────────────
    private static final Pattern ARM_RESOURCE_ID = Pattern.compile(
            "^/subscriptions/[a-f0-9\\-]{36}/resourceGroups/[a-zA-Z0-9_\\-.]{1,90}"
                    + "/providers/[a-zA-Z0-9.]+/[a-zA-Z0-9_\\-]+/[a-zA-Z0-9_\\-]{1,260}$");

    private static final Pattern SAFE_TAG_KEY = Pattern.compile("^[a-zA-Z0-9_\\-]{1,512}$");
    private static final Pattern SAFE_TAG_VALUE = Pattern.compile("^[a-zA-Z0-9_\\- .:@]{0,256}$");

    // ── Command whitelist ────────────────────────────────────────────────────
    private static final String ALLOWED_COMMAND = "az";
    private static final String ALLOWED_SUBCOMMAND = "tag";
    private static final String ALLOWED_OPERATION = "update";

    /**
     * Applies tags to an Azure resource asynchronously.
     *
     * @param resourceId full ARM resource ID
     * @param tags       map of tag keys → values (already validated by DTO)
     * @return CompletableFuture that completes with a status message
     */
    @Async("remediationTaskExecutor")
    public CompletableFuture<String> applyTagsAsync(String resourceId, Map<String, String> tags) {
        long start = System.currentTimeMillis();

        try {
            // ── Defence in depth: re-validate everything on the service layer ──
            validateResourceId(resourceId);
            validateTags(tags);

            // ── Build command as String[] — zero string interpolation into shell ──
            List<String> command = buildTagCommand(resourceId, tags);
            log.info("[Remediation] Executing: {}", maskSecrets(command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true); // merge stderr into stdout

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            long duration = System.currentTimeMillis() - start;

            if (exitCode == 0) {
                log.info("[Remediation] SUCCESS for '{}' in {} ms. Output: {}",
                        resourceId, duration, output.toString().trim());
                return CompletableFuture.completedFuture("SUCCESS");
            } else {
                log.error("[Remediation] FAILURE for '{}' (exit={}) after {} ms.\nOutput:\n{}",
                        resourceId, exitCode, duration, output);
                return CompletableFuture.completedFuture("FAILURE: exit code " + exitCode);
            }

        } catch (IllegalArgumentException e) {
            log.error("[Remediation] VALIDATION FAILED for '{}': {}", resourceId, e.getMessage());
            return CompletableFuture.completedFuture("VALIDATION_ERROR: " + e.getMessage());
        } catch (IOException e) {
            log.error("[Remediation] IO ERROR for '{}': {}. Is 'az' CLI installed?",
                    resourceId, e.getMessage());
            return CompletableFuture.completedFuture("IO_ERROR: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Remediation] INTERRUPTED for '{}'", resourceId);
            return CompletableFuture.completedFuture("INTERRUPTED");
        } catch (Exception e) {
            log.error("[Remediation] UNEXPECTED ERROR for '{}'", resourceId, e);
            return CompletableFuture.completedFuture("UNEXPECTED: " + e.getMessage());
        }
    }

    // ── Command construction (no shell string interpolation!) ────────────────

    private List<String> buildTagCommand(String resourceId, Map<String, String> tags) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ALLOWED_COMMAND);        // "az"
        cmd.add(ALLOWED_SUBCOMMAND);     // "tag"
        cmd.add(ALLOWED_OPERATION);      // "update"
        cmd.add("--resource-id");
        cmd.add(resourceId);             // already validated
        cmd.add("--operation");
        cmd.add("merge");                // merge = add/update, never delete

        // Build --tags key1=value1 key2=value2 …
        cmd.add("--tags");
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            // Values with spaces are quoted by ProcessBuilder automatically
            // because we pass them as separate array elements
            cmd.add(entry.getKey() + "=" + entry.getValue());
        }

        return cmd;
    }

    // ── Validation ─────────────────────────────────────────────────────────────

    private void validateResourceId(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId is null or blank");
        }
        if (!ARM_RESOURCE_ID.matcher(resourceId).matches()) {
            throw new IllegalArgumentException("resourceId does not match ARM format: " + resourceId);
        }
    }

    private void validateTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            throw new IllegalArgumentException("tags map is null or empty");
        }
        if (tags.size() > 15) {
            throw new IllegalArgumentException("too many tags (max 15): " + tags.size());
        }
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !SAFE_TAG_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("illegal tag key: '" + key + "'");
            }
            if (value == null || !SAFE_TAG_VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("illegal tag value for key '" + key + "': '" + value + "'");
            }
        }
    }

    // ── Logging helpers ──────────────────────────────────────────────────────

    private String maskSecrets(List<String> command) {
        // In a real scenario we might mask client secrets; here the command
        // only contains resource IDs and tag values which are fine to log.
        return String.join(" ", command);
    }
}
