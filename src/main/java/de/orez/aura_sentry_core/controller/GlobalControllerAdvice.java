package de.orez.aura_sentry_core.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import de.orez.aura_sentry_core.config.ActiveAccount;
import de.orez.aura_sentry_core.config.SessionAccountManager;
import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import de.orez.aura_sentry_core.persistence.repository.UserRepository;
import jakarta.servlet.http.HttpSession;

@ControllerAdvice
public class GlobalControllerAdvice {

    private final UserRepository userRepository;
    private final SessionAccountManager sessionAccountManager;

    public GlobalControllerAdvice(UserRepository userRepository, SessionAccountManager sessionAccountManager) {
        this.userRepository = userRepository;
        this.sessionAccountManager = sessionAccountManager;
    }

    @ModelAttribute("currentUser")
    public UserEntity currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    @ModelAttribute("activeAccounts")
    public List<ActiveAccount> activeAccounts(HttpSession session) {
        return sessionAccountManager.getAccounts(session);
    }
}
