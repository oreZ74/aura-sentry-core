package de.orez.aura_sentry_core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.core.context.SecurityContextHolder;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;

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
		loadEnvironmentVariables();
		SpringApplication.run(AuraSentryCoreApplication.class, args);
	}

	/**
	 * Loads environment variables from the .env file and registers them as system
	 * properties so that Spring's ${...} placeholders in application.properties
	 * are resolved correctly.
	 *
	 * Without this, values like ${POSTGRES_USER} stay literal and the PostgreSQL
	 * connection fails with "user '${POSTGRES_USER}' not found".
	 */
	private static void loadEnvironmentVariables() {
		try {
			Dotenv dotenv = Dotenv.configure()
					.ignoreIfMissing()
					.load();

			int loaded = 0;
			for (DotenvEntry entry : dotenv.entries()) {
				String key = entry.getKey();
				if (System.getProperty(key) == null) {
					System.setProperty(key, entry.getValue());
					loaded++;
				}
			}

			logger.info("[AuraSentry] {} variables loaded from .env", loaded);
			logger.info("[AuraSentry] Datasource User: {} (from {})",
					System.getProperty("POSTGRES_USER", "not set"),
					System.getProperty("POSTGRES_USER") != null ? ".env" : "application.properties default");

		} catch (Exception e) {
			logger.warn("Failed to load .env file: {}", e.getMessage());
		}
	}

}
