package de.orez.aura_sentry_core;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for all integration tests that require a Spring
 * {@link org.springframework.context.ApplicationContext} with a running
 * PostgreSQL database.
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li><b>postgres:16-alpine</b> – lightweight image matching the production
 *       major version.</li>
 *   <li><b>{@link Container @Container} static</b> – the PostgreSQL instance is
 *       started <em>once</em> per JVM and shared across all subclasses. This
 *       dramatically reduces test-suite start-up time.</li>
 *   <li><b>{@link ServiceConnection @ServiceConnection}</b> – Spring Boot
 *       automatically overrides {@code spring.datasource.*} properties with the
 *       container's JDBC URL, username and password.</li>
 *   <li><b>{@link Testcontainers#disabledWithoutDocker() disabledWithoutDocker}</b>
 *       – the entire suite is skipped gracefully when Docker is not available
 *       (e.g. in CI environments without a Docker daemon).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
}
