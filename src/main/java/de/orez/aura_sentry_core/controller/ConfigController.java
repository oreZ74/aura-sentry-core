package de.orez.aura_sentry_core.controller;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.orez.aura_sentry_core.controller.dto.config.CloudCredentialsRequest;
import de.orez.aura_sentry_core.controller.dto.config.CloudCredentialsResponse;
import de.orez.aura_sentry_core.persistence.entity.AiConfigEntity;
import de.orez.aura_sentry_core.persistence.entity.CloudCredentialsEntity;
import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import de.orez.aura_sentry_core.persistence.repository.AiConfigRepository;
import de.orez.aura_sentry_core.persistence.repository.CloudCredentialsRepository;
import de.orez.aura_sentry_core.persistence.repository.UserRepository;
import de.orez.aura_sentry_core.service.CredentialEncryptionService;
import de.orez.aura_sentry_core.service.CredentialManagementService;
import de.orez.aura_sentry_core.service.demo.DemoModeProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * REST endpoint for managing Azure and Gemini credentials.
 *
 * <h3>Security design</h3>
 * <ul>
 *   <li>Secrets (client secret, Gemini key) are <b>never returned</b> in
 *       plaintext from the GET endpoint – a fixed mask
 *       ({@value #SECRET_MASK}) is returned instead.</li>
 *   <li>On update, a blank or masked value means &quot;preserve the existing
 *       encrypted secret&quot;. Only a genuinely new value triggers
 *       re-encryption.</li>
 *   <li>Demo mode returns a completely static response and rejects all
 *       writes with 403.</li>
 * </ul>
 */
@Tag(name = "Configuration", description = "Manage Azure service-principal and Gemini API credentials")
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private static final String SECRET_MASK = "••••••••••••••••";

    /**
     * Shortened, human-readable version of the 90-line system prompt shown as
     * a placeholder in the AI Advisor Configuration modal when the user has
     * not defined a custom system template. Contains the available
     * {@code {{placeholders}}} so users know which variables they can
     * interpolate into their custom prompts.
     */
    static final String SYSTEM_TEMPLATE_PLACEHOLDER = """
            Default System Prompt (active when this field is empty):

            You are a Senior Cloud Engineer with deep expertise in Azure, Terraform, Azure PowerShell, Bicep, and Azure CLI.
            You analyse cloud resource configurations and respond with structured JSON matching this schema:

            {
              "summary": "Brief root-cause analysis (max 2 sentences)",
              "issueDetected": true,
              "issueType": "HEALTHY | GOVERNANCE_MISSING | OVERPROVISIONED | UNDERUTILIZED | ORPHANED | SECURITY_RISK | COST_OPTIMIZATION",
              "savingsPotentialUsd": 0.0,
              "severity": "NONE | LOW | MEDIUM | HIGH | CRITICAL",
              "remediationScripts": {
                "description": "What the remediation accomplishes (one sentence)",
                "azureCli": "Azure CLI script or command",
                "terraform": "Terraform HCL resource block",
                "azurePowerShell": "Azure PowerShell script",
                "bicep": "Bicep module definition",
                "requiredRbacRoles": ["Virtual Machine Contributor", "Storage Account Contributor"]
              },
              "recommendations": ["Concrete action item 1", "action item 2"]
            }

            Available placeholders for custom prompts:
              {{resourceName}}  {{resourceType}}  {{resourceGroup}}  {{region}}  {{sku}}  {{state}}
              {{flag}}  {{reason}}  {{tags}}  {{cost}}  {{costSource}}

            Leave this field empty to use the full default system prompt (90+ lines with detailed
            classification rules, telemetry analysis, and deterministic issue classification).""";

    static final String SPECIFIC_INSTRUCTIONS_PLACEHOLDER = """
            Examples:
              - "Focus on cost savings for LRS storage accounts."
              - "Output only Terraform code examples."
              - "Prioritise security risk analysis for production resources."
            Leave empty for no extra instructions.""";

    private final CloudCredentialsRepository credentialsRepository;
    private final CredentialEncryptionService encryptionService;
    private final UserRepository userRepository;
    private final AiConfigRepository aiConfigRepository;
    private final CredentialManagementService credentialManagementService;
    private final DemoModeProvider demoModeProvider;

    /**
     * Demo-mode response – completely static, NEVER touches the database.
     */
    private static final CloudCredentialsResponse DEMO_RESPONSE = new CloudCredentialsResponse(
            999L,
            "demo-tenant-123",
            "demo-client-123",
            SECRET_MASK,
            "demo-subscription-123",
            SECRET_MASK,
            true,
            "You are a cloud cost expert. Analyse {{resourceName}} ({{resourceType}}) and suggest optimizations.",
            "",
            SYSTEM_TEMPLATE_PLACEHOLDER,
            SPECIFIC_INSTRUCTIONS_PLACEHOLDER);

    // ════════════════════════════════════════════════════════════════════════
    // GET  /api/v1/config
    // ════════════════════════════════════════════════════════════════════════

    @Operation(summary = "Retrieve current credentials", description = "Returns saved Azure and Gemini config. Secrets are masked.")
    @GetMapping
    public ResponseEntity<CloudCredentialsResponse> getCredentials(
            @AuthenticationPrincipal UserDetails userDetails) {

        if (demoModeProvider.isDemoMode()) {
            log.info("[Config] GET – demo mode → static response");
            return ResponseEntity.ok(DEMO_RESPONSE);
        }

        String username = resolveUsername(userDetails);
        UserEntity user = userRepository.findByUsername(username).orElse(null);

        if (user == null) {
            log.info("[Config] GET – user '{}' not found → unconfigured", username);
            return ResponseEntity.ok(CloudCredentialsResponse.unconfigured(
                    SYSTEM_TEMPLATE_PLACEHOLDER, SPECIFIC_INSTRUCTIONS_PLACEHOLDER));
        }

        var credsOpt = credentialsRepository.findTopByUserOrderByUpdatedAtDesc(user);
        var aiConfigOpt = aiConfigRepository.findByUserUsername(username);

        if (credsOpt.isEmpty()) {
            log.info("[Config] GET – no credentials for '{}' → unconfigured", username);
            return ResponseEntity.ok(CloudCredentialsResponse.unconfigured(
                    SYSTEM_TEMPLATE_PLACEHOLDER, SPECIFIC_INSTRUCTIONS_PLACEHOLDER));
        }

        CloudCredentialsResponse response = toResponse(credsOpt.get(), aiConfigOpt.orElse(null));
        log.info("[Config] GET – credentials id={}, user='{}'", credsOpt.get().getId(), username);
        return ResponseEntity.ok(response);
    }

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/v1/config
    // ════════════════════════════════════════════════════════════════════════

    @Operation(summary = "Save credentials", description = "Encrypts and stores Azure service-principal and Gemini API key. Blank secrets preserve the previous value.")
    @PostMapping
    public ResponseEntity<?> saveCredentials(
            @Valid @RequestBody CloudCredentialsRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (demoModeProvider.isDemoMode()) {
            log.warn("[Config] POST rejected – demo mode active");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Demo mode: credential changes are not persisted. "
                            + "Switch to live mode to save real credentials.");
        }

        try {
            String username = resolveUsername(userDetails);
            log.info("[Config] POST – saving credentials for '{}'", username);

            UserEntity currentUser = userRepository.findByUsername(username)
                    .orElseThrow(() -> new IllegalStateException(
                            "User '" + username + "' not found. Has UserDataInitializer run?"));

            CloudCredentialsEntity saved = credentialManagementService.saveCredentials(
                    currentUser,
                    request.tenantId(), request.clientId(), request.clientSecret(),
                    request.subscriptionId(), request.geminiApiKey(),
                    request.systemTemplate(), request.specificInstructions());

            AiConfigEntity aiConfig = aiConfigRepository.findByUserUsername(username).orElse(null);
            return ResponseEntity.ok(toResponse(saved, aiConfig));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("[Config] Save failed – {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to save credentials: " + e.getClass().getSimpleName()
                            + " – " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // DTO mapping
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Maps entity to response DTO.
     * <p>
     * Tenant/client/subscription IDs and secrets are returned in plaintext.
     * The frontend initially shows a fixed mask ({@value #SECRET_MASK}) and
     * only reveals the real value when the user clicks the eye toggle.
     * The mask string is also recognized by {@link #isBlankOrPlaceholder(String)}
     * so unchanged secrets are preserved on update.
     */
    private CloudCredentialsResponse toResponse(CloudCredentialsEntity entity, AiConfigEntity aiConfig) {
        return new CloudCredentialsResponse(
                entity.getId(),
                decryptSafe(entity.getEncryptedTenantId()),
                decryptSafe(entity.getEncryptedClientId()),
                decryptSafe(entity.getEncryptedClientSecret()),
                decryptSafe(entity.getEncryptedSubscriptionId()),
                decryptSafe(entity.getEncryptedGeminiApiKey()),
                true,
                aiConfig != null ? aiConfig.getSystemTemplate() : null,
                aiConfig != null ? aiConfig.getSpecificInstructions() : null,
                SYSTEM_TEMPLATE_PLACEHOLDER,
                SPECIFIC_INSTRUCTIONS_PLACEHOLDER);
    }

    private String decryptSafe(String ciphertext) {
        try {
            return encryptionService.decrypt(ciphertext);
        } catch (Exception e) {
            log.error("[Config] Decryption failed for display", e);
            return "***decrypt-error***";
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private static String resolveUsername(UserDetails userDetails) {
        if (userDetails == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return userDetails.getUsername();
    }
}
