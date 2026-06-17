package de.orez.aura_sentry_core.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import de.orez.aura_sentry_core.service.demo.DemoModeProvider;

/**
 * Request-scoped filter that bridges the HTTP-session demo flag into the
 * {@link DemoModeProvider} {@code ThreadLocal}.
 *
 * <p>Problem solved: {@code DemoModeProvider} stores the flag per thread.
 * Tomcat dispatches each HTTP request onto a new worker thread (or virtual
 * thread). Without this filter the thread-local flag is lost after the very
 * first request that set it (e.g. the initial page render). Subsequent AJAX
 * calls therefore see {@code isDemoMode() == false} and fall back to the DB
 * cache, overwriting the demo view with live Azure data.
 *
 * <p>This filter runs early (high precedence, {@link Ordered#HIGHEST_PRECEDENCE})
 * so that every controller, service and repository downstream sees the same
 * {@code DemoModeProvider} state without any code changes in the business layer.
 *
 * <p>Both session-based and header-based activation are supported:
 * <ul>
 *   <li>Session: {@code session.getAttribute("demoModeActive") == true}</li>
 *   <li>Header: {@code X-Demo-Mode: true}</li>
 * </ul>
 *
 * <p>Crucially the filter calls {@link DemoModeProvider#clear()} in a
 * {@code finally} block. This prevents context leaks when virtual threads
 * are pooled / reused by the underlying carrier thread.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DemoModeFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DemoModeFilter.class);

    private static final String DEMO_SESSION_KEY = "demoModeActive";
    private static final String DEMO_HEADER = "X-Demo-Mode";

    private final DemoModeProvider demoModeProvider;

    public DemoModeFilter(DemoModeProvider demoModeProvider) {
        this.demoModeProvider = demoModeProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        boolean activated = false;
        try {
            // ── 1. Resolve demo flag from Session OR Header ──
            HttpSession session = request.getSession(false);
            boolean sessionDemo = (session != null)
                    && Boolean.TRUE.equals(session.getAttribute(DEMO_SESSION_KEY));

            boolean headerDemo = "true".equalsIgnoreCase(request.getHeader(DEMO_HEADER));

            if (sessionDemo || headerDemo) {
                demoModeProvider.setDemoMode(true);
                activated = true;
                log.debug("[DemoModeFilter] Activated for {} {} (session={}, header={})",
                        request.getMethod(), request.getRequestURI(), sessionDemo, headerDemo);
            }

            // ── 2. Continue the filter chain ──
            filterChain.doFilter(request, response);

        } finally {
            // ── 3. ALWAYS clean up to avoid ThreadLocal leaks ──
            // Virtual threads may be mounted on pooled carrier threads;
            // leaving the flag set would poison the next request.
            if (activated) {
                log.debug("[DemoModeFilter] Clearing demo context for {} {}",
                        request.getMethod(), request.getRequestURI());
            }
            demoModeProvider.clear();
        }
    }
}
