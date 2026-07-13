package de.orez.aura_sentry_core.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Führt Flyway-Migrationen aus, BEVOR Spring den ApplicationContext baut.
 *
 * <p>Warum ein {@link ApplicationListener} statt {@code @Bean(initMethod =
 * "migrate")}? In Spring Boot 4.0.5 greift weder die
 * {@code FlywayAutoConfiguration} noch eine manuelle {@code @Configuration}
 * mit {@code @ConditionalOnProperty} zuverlässig – Flyway blieb komplett
 * untätig. Dieser Listener wird per {@code spring.factories} registriert,
 * läuft vor dem Context-Refresh und erzeugt eine eigenständige
 * {@link DataSource}, sodass Flyway unabhängig vom Spring-
 * Bean-Lebenszyklus ist.
 */
public class FlywayMigrationRunner implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationRunner.class);

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Environment env = event.getEnvironment();

        if (!Boolean.parseBoolean(env.getProperty("spring.flyway.enabled", "true"))) {
            log.info("[flyway] Disabled via spring.flyway.enabled=false – skipping migrations");
            return;
        }

        String url = env.getProperty("spring.datasource.url");
        String user = env.getProperty("spring.datasource.username");
        String password = env.getProperty("spring.datasource.password");
        String driver = env.getProperty("spring.datasource.driver-class-name");
        String locations = env.getProperty("spring.flyway.locations", "classpath:db/migration");
        boolean baselineOnMigrate = Boolean.parseBoolean(
                env.getProperty("spring.flyway.baseline-on-migrate", "true"));
        String baselineVersion = env.getProperty("spring.flyway.baseline-version", "0");

        if (url == null || user == null) {
            log.warn("[flyway] Datasource properties not yet resolved – skipping pre-context migration");
            return;
        }

        log.info("[flyway] Starting pre-context migration on {}", url);

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        if (driver != null) hikari.setDriverClassName(driver);
        hikari.setMaximumPoolSize(2);

        try (HikariDataSource ds = new HikariDataSource(hikari)) {
            Flyway flyway = Flyway.configure()
                    .dataSource(ds)
                    .locations(locations)
                    .baselineOnMigrate(baselineOnMigrate)
                    .baselineVersion(MigrationVersion.fromVersion(baselineVersion))
                    .load();

            int applied = flyway.migrate().migrationsExecuted;
            log.info("[flyway] Migration complete – {} migration(s) executed", applied);
        } catch (Exception e) {
            log.error("[flyway] Migration failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Flyway migration failed – aborting startup", e);
        }
    }
}
