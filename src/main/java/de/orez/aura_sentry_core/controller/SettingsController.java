package de.orez.aura_sentry_core.controller;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import de.orez.aura_sentry_core.persistence.entity.CloudCredentialsEntity;
import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import de.orez.aura_sentry_core.persistence.repository.CloudCredentialsRepository;
import de.orez.aura_sentry_core.persistence.repository.UserRepository;
import de.orez.aura_sentry_core.service.CredentialEncryptionService;
import de.orez.aura_sentry_core.service.CredentialManagementService;
import de.orez.aura_sentry_core.service.UserDeletionService;
import jakarta.servlet.http.HttpSession;

@Controller
@RequiredArgsConstructor
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final UserRepository userRepository;
    private final CloudCredentialsRepository credentialsRepository;
    private final CredentialEncryptionService encryptionService;
    private final PasswordEncoder passwordEncoder;
    private final UserDeletionService userDeletionService;
    private final CredentialManagementService credentialManagementService;

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("currentPage", "settings");
        model.addAttribute("activeTab", "advanced");

        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            String username = auth.getName();
            UserEntity user = userRepository.findByUsername(username).orElse(null);
            if (user != null) {
                var creds = credentialsRepository.findTopByUserOrderByUpdatedAtDesc(user);
                if (creds.isPresent()) {
                    model.addAttribute("credentials", toView(creds.get()));
                } else {
                    model.addAttribute("credentials", CredentialsView.empty());
                }
            }
        }
        return "settings";
    }

    private CredentialsView toView(CloudCredentialsEntity entity) {
        return new CredentialsView(
                decryptSafe(entity.getEncryptedTenantId()),
                decryptSafe(entity.getEncryptedClientId()),
                decryptSafe(entity.getEncryptedClientSecret()),
                decryptSafe(entity.getEncryptedSubscriptionId()),
                decryptSafe(entity.getEncryptedGeminiApiKey()),
                true);
    }

    private String decryptSafe(String ciphertext) {
        try {
            return encryptionService.decrypt(ciphertext);
        } catch (Exception e) {
            log.warn("Decryption failed for display: {}", e.getMessage());
            return "••••••••";
        }
    }

    public record CredentialsView(String tenantId, String clientId, String clientSecret,
                                   String subscriptionId, String geminiApiKey, boolean configured) {
        public static CredentialsView empty() {
            return new CredentialsView("", "", "", "", "", false);
        }
    }

    @PostMapping("/settings/profile")
    @Transactional
    public String updateProfile(
            @RequestParam String fullName,
            @RequestParam String email,
            @AuthenticationPrincipal UserDetails userDetails,
            RedirectAttributes redirectAttributes) {

        String username = userDetails != null ? userDetails.getUsername() : null;
        if (username == null) {
            redirectAttributes.addFlashAttribute("error", "Not authenticated.");
            return "redirect:/settings";
        }

        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "User not found.");
            return "redirect:/settings";
        }

        user.setFullName(fullName);
        user.setEmail(email);
        userRepository.save(user);

        redirectAttributes.addFlashAttribute("success", "Profile updated successfully.");
        return "redirect:/settings";
    }

    @PostMapping("/settings/credentials")
    public String saveCredentials(
            @RequestParam String tenantId,
            @RequestParam String clientId,
            @RequestParam(required = false) String clientSecret,
            @RequestParam String subscriptionId,
            @RequestParam(required = false) String geminiApiKey,
            @RequestParam(required = false) String systemTemplate,
            @RequestParam(required = false) String specificInstructions,
            @AuthenticationPrincipal UserDetails userDetails,
            RedirectAttributes redirectAttributes) {

        String username = userDetails != null ? userDetails.getUsername() : null;
        if (username == null) {
            redirectAttributes.addFlashAttribute("error", "Not authenticated.");
            return "redirect:/settings";
        }

        UserEntity currentUser = userRepository.findByUsername(username).orElse(null);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "User not found.");
            return "redirect:/settings";
        }

        try {
            credentialManagementService.saveCredentials(
                    currentUser,
                    tenantId, clientId, clientSecret != null ? clientSecret : "",
                    subscriptionId, geminiApiKey != null ? geminiApiKey : "",
                    systemTemplate, specificInstructions);

            redirectAttributes.addFlashAttribute("success", "Credentials saved successfully.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to save credentials: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "Failed to save: " + e.getMessage());
        }

        return "redirect:/settings";
    }

    @PostMapping("/settings/password")
    @Transactional
    public String changePassword(
            @RequestParam String currentPassword,
            @RequestParam String newPassword,
            @RequestParam String confirmPassword,
            @AuthenticationPrincipal UserDetails userDetails,
            RedirectAttributes redirectAttributes) {

        String username = userDetails != null ? userDetails.getUsername() : null;
        if (username == null) {
            redirectAttributes.addFlashAttribute("error", "Not authenticated.");
            return "redirect:/settings";
        }

        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "User not found.");
            return "redirect:/settings";
        }

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            redirectAttributes.addFlashAttribute("error", "Current password is incorrect.");
            return "redirect:/settings";
        }

        if (!newPassword.equals(confirmPassword)) {
            redirectAttributes.addFlashAttribute("error", "New password and confirmation do not match.");
            return "redirect:/settings";
        }

        if (newPassword.length() < 8) {
            redirectAttributes.addFlashAttribute("error", "New password must be at least 8 characters.");
            return "redirect:/settings";
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        redirectAttributes.addFlashAttribute("success", "Password updated successfully.");
        return "redirect:/settings";
    }

    /**
     * Permanently deletes the currently authenticated user's account.
     * All associated data (credentials, AI config, notifications) is
     * removed before the user row. The session is invalidated and the
     * SecurityContext is cleared so that no stale authentication state
     * survives the redirect.
     */
    @PostMapping("/settings/account/delete")
    @Transactional
    public String deleteAccount(
            @AuthenticationPrincipal UserDetails userDetails,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String username = userDetails != null ? userDetails.getUsername() : null;
        if (username == null) {
            redirectAttributes.addFlashAttribute("error", "Not authenticated.");
            return "redirect:/settings";
        }

        try {
            userDeletionService.deleteAccount(username);
        } catch (IllegalArgumentException e) {
            log.warn("Account deletion failed for '{}': {}", username, e.getMessage());
            redirectAttributes.addFlashAttribute("error", "Account not found.");
            return "redirect:/settings";
        } catch (Exception e) {
            log.error("Account deletion failed for '{}': {}", username, e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error",
                    "Failed to delete account. Please try again.");
            return "redirect:/settings";
        }

        session.invalidate();
        SecurityContextHolder.clearContext();

        log.info("Account '{}' deleted – session invalidated, redirecting to login", username);
        return "redirect:/login?deleted=true";
    }
}
