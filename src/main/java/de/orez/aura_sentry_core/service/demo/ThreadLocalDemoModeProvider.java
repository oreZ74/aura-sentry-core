package de.orez.aura_sentry_core.service.demo;

import org.springframework.stereotype.Component;

/**
 * ThreadLocal-based implementation of {@link DemoModeProvider}.
 *
 * <p>Each virtual thread carries its own flag, so concurrent requests
 * are fully isolated without any synchronisation.
 */
@Component
public class ThreadLocalDemoModeProvider implements DemoModeProvider {

    private final ThreadLocal<Boolean> demoMode = ThreadLocal.withInitial(() -> false);

    @Override
    public boolean isDemoMode() {
        return demoMode.get();
    }

    @Override
    public void setDemoMode(boolean demo) {
        demoMode.set(demo);
    }

    @Override
    public void clear() {
        demoMode.remove();
    }
}
