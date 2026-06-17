package de.orez.aura_sentry_core.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import de.orez.aura_sentry_core.persistence.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;

@Controller
public class SwitchAccountController {

    private static final Logger log = LoggerFactory.getLogger(SwitchAccountController.class);

    private final UserRepository userRepository;

    public SwitchAccountController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/switch-account")
    public String switchAccount(@RequestParam String email, HttpServletRequest request) {
        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + email));

        var authorities = java.util.List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
        var auth = new UsernamePasswordAuthenticationToken(user.getUsername(), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
        request.getSession().setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                SecurityContextHolder.getContext());
        log.info("[AuraSentry] Switched active account to: {}", email);
        return "redirect:/";
    }
}
