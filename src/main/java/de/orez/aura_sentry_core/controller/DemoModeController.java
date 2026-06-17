package de.orez.aura_sentry_core.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpSession;

/**
 * REST endpoint for manually toggling demo mode.
 *
 * <p>Demo mode is <b>never</b> activated automatically by the application.
 * It can only be enabled or disabled by the user via the "Try Demo Mode"
 * button in the dashboard UI.
 *
 * <p>The flag is stored in the HTTP session so it persists across requests
 * within the same browser session.
 */
@RestController
@RequestMapping("/api/v1/demo")
public class DemoModeController {

    private static final Logger log = LoggerFactory.getLogger(DemoModeController.class);
    private static final String SESSION_KEY = "demoModeActive";

    /**
     * Toggles the demo mode flag in the user's session.
     *
     * <p>If demo mode is currently OFF → turns it ON.
     * If demo mode is currently ON  → turns it OFF.
     *
     * @return the new state of the demo mode flag
     */
    @PostMapping("/toggle")
    public ResponseEntity<DemoToggleResponse> toggleDemoMode(HttpSession session) {
        Boolean current = (Boolean) session.getAttribute(SESSION_KEY);
        boolean newState = (current == null || !current);
        session.setAttribute(SESSION_KEY, newState);

        log.info("[DemoMode] Toggled to {} (session={})", newState, session.getId());

        return ResponseEntity.ok(new DemoToggleResponse(newState));
    }

    /**
     * Explicitly sets demo mode to a specific state.
     */
    @PostMapping("/enable")
    public ResponseEntity<DemoToggleResponse> enableDemoMode(HttpSession session) {
        session.setAttribute(SESSION_KEY, true);
        log.info("[DemoMode] Enabled (session={})", session.getId());
        return ResponseEntity.ok(new DemoToggleResponse(true));
    }

    @PostMapping("/disable")
    public ResponseEntity<DemoToggleResponse> disableDemoMode(HttpSession session) {
        session.setAttribute(SESSION_KEY, false);
        log.info("[DemoMode] Disabled (session={})", session.getId());
        return ResponseEntity.ok(new DemoToggleResponse(false));
    }

    public record DemoToggleResponse(boolean demoModeActive) {}
}
