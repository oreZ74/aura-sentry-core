package de.orez.aura_sentry_core;

import de.orez.aura_sentry_core.config.FlywayMigrationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.core.context.SecurityContextHolder;

@SpringBootApplication
@EnableAsync
@EnableRetry
@ConfigurationPropertiesScan
public class AuraSentryCoreApplication {

    private static final Logger logger = LoggerFactory.getLogger(AuraSentryCoreApplication.class);

    static {
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AuraSentryCoreApplication.class);
        app.addListeners(new FlywayMigrationRunner());
        app.run(args);
        logger.info("[AuraSentry] Application started successfully");
    }

}
