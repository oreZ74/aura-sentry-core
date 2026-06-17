package de.orez.aura_sentry_core.controller;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import de.orez.aura_sentry_core.config.CustomUserDetailsService;
import de.orez.aura_sentry_core.config.SessionAccountManager;
import de.orez.aura_sentry_core.persistence.entity.AiConfigEntity;
import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import de.orez.aura_sentry_core.persistence.repository.AiConfigRepository;
import de.orez.aura_sentry_core.persistence.repository.UserRepository;
import jakarta.servlet.http.HttpSession;

@Controller
public class AuthController {

    // Defaults are intentionally null / empty so that RemediationAgent's built-in
    // fallback (RemediationPromptBuilder.SYSTEM_PROMPT) is used for new users.
    // The fallback prompt enforces the exact JSON schema including mandatory enums,
    // avoiding NullPointerException on issueType.name() downstream.
    // Users can later set custom templates via the AI Config UI.
    private static final String DEFAULT_SYSTEM_TEMPLATE = null;
    private static final String DEFAULT_SPECIFIC_INSTRUCTIONS = null;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionAccountManager sessionAccountManager;
    private final CustomUserDetailsService userDetailsService;
    private final AiConfigRepository aiConfigRepository;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder,
            SessionAccountManager sessionAccountManager, CustomUserDetailsService userDetailsService,
            AiConfigRepository aiConfigRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionAccountManager = sessionAccountManager;
        this.userDetailsService = userDetailsService;
        this.aiConfigRepository = aiConfigRepository;
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    @PostMapping("/register")
    public String register(
            @RequestParam String fullName,
            @RequestParam String username,
            @RequestParam String email,
            @RequestParam String password,
            @RequestParam String confirmPassword,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!password.equals(confirmPassword)) {
            redirectAttributes.addFlashAttribute("error", "Passwords do not match.");
            return "redirect:/register";
        }

        if (userRepository.existsByUsername(username)) {
            redirectAttributes.addFlashAttribute("error", "An account with this username already exists.");
            return "redirect:/register";
        }

        if (userRepository.existsByEmail(email)) {
            redirectAttributes.addFlashAttribute("error", "An account with this email address already exists.");
            return "redirect:/register";
        }

        UserEntity user = new UserEntity(username, passwordEncoder.encode(password), "USER");
        user.setFullName(fullName);
        user.setEmail(email);
        user.setEnabled(true);
        UserEntity saved = userRepository.save(user);

        AiConfigEntity aiConfig = new AiConfigEntity();
        aiConfig.setUser(saved);
        aiConfig.setSystemTemplate(DEFAULT_SYSTEM_TEMPLATE);
        aiConfig.setSpecificInstructions(DEFAULT_SPECIFIC_INSTRUCTIONS);
        aiConfig.setUpdatedAt(java.time.Instant.now());
        aiConfigRepository.save(aiConfig);

        sessionAccountManager.addAccount(session, saved);

        var userDetails = userDetailsService.loadUserByUsername(saved.getUsername());
        var auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        return "redirect:/";
    }
}
