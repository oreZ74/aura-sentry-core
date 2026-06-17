package de.orez.aura_sentry_core.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Async executor for non-blocking remediation tasks.
 *
 * <p>Uses virtual threads ({@code Executors.newVirtualThreadPerTaskExecutor})
 * because remediation jobs (Azure CLI {@code ProcessBuilder}) are pure I/O
 * waits. Virtual threads avoid the overhead of sizing a platform thread pool
 * for an unknown number of concurrent tag-update jobs.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("remediationTaskExecutor")
    public Executor remediationTaskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
