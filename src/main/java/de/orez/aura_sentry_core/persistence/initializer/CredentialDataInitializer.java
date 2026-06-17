package de.orez.aura_sentry_core.persistence.initializer;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Former placeholder-credentials seeder.
 *
 * <p>In a multi-user system, each user manages their own cloud credentials
 * via the Settings UI. Placeholder data is no longer injected on startup.
 * This initializer is retained as a no-op for backward compatibility with
 * the {@code @Order(10)} execution contract.
 */
@Component
@Order(10)
@RequiredArgsConstructor
public class CredentialDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CredentialDataInitializer.class);

    @Override
    public void run(ApplicationArguments args) {
        log.info("[Init] CredentialDataInitializer: seeding disabled (user-managed credentials via Settings UI)");
    }
}
