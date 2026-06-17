package de.orez.aura_sentry_core.service.demo;

/**
 * Injectable abstraction for demo-mode flag management, replacing the
 * static {@code DemoModeContext} ThreadLocal pattern.
 */
public interface DemoModeProvider {

    boolean isDemoMode();

    void setDemoMode(boolean demo);

    void clear();
}
