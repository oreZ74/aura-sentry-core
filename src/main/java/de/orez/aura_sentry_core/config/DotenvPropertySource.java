package de.orez.aura_sentry_core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Lädt {@code .env} aus dem Projekt-Wurzelverzeichnis und registriert alle
 * Einträge als {@link MapPropertySource} mit höchster Priorität im Spring
 * {@link org.springframework.core.env.Environment}.
 *
 * <p><b>Warum kein {@code java-dotenv} mehr?</b>
 * {@code io.github.cdimascio:java-dotenv} arbeitet mit
 * {@code System.setProperty()} außerhalb von Springs Lebenszyklus.
 * Spring Boot DevTools erzeugt beim Hot-Reload einen neuen
 * {@code RestartClassLoader}, der die bereits gesetzten System-Properties
 * des ersten Starts nicht mehr sieht. Ein {@code EnvironmentPostProcessor}
 * wird dagegen bei jedem Kontext-Neustart zuverlässig ausgeführt und
 * integriert die {@code .env}-Werte direkt in Springs
 * {@link ConfigurableEnvironment} – DevTools-fest.
 *
 * <p><b>Format:</b> Standard {@code KEY=VALUE} mit optionalen Kommentaren
 * ({@code #}). Leerzeilen und Zeilen ohne {@code =} werden ignoriert.
 */
public class DotenvPropertySource implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DotenvPropertySource.class);

    private static final String PROPERTY_SOURCE_NAME = "dotenv";
    private static final String ENV_FILE_NAME = ".env";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                        SpringApplication application) {
        Path envFile = locateEnvFile();
        if (envFile == null || !Files.isRegularFile(envFile)) {
            log.debug("[dotenv] No .env file found – skipping property source");
            return;
        }

        Map<String, Object> properties = parseEnvFile(envFile);
        if (properties.isEmpty()) {
            log.debug("[dotenv] .env file is empty – skipping property source");
            return;
        }

        MapPropertySource source = new MapPropertySource(PROPERTY_SOURCE_NAME, properties);
        environment.getPropertySources().addFirst(source);

        log.info("[dotenv] {} variable(s) loaded from {}", properties.size(), envFile.toAbsolutePath());
    }

    private Path locateEnvFile() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path envPath = cwd.resolve(ENV_FILE_NAME);
        if (Files.exists(envPath)) {
            return envPath;
        }

        Path parent = cwd;
        for (int i = 0; i < 5; i++) {
            parent = parent.getParent();
            if (parent == null) break;
            envPath = parent.resolve(ENV_FILE_NAME);
            if (Files.exists(envPath)) {
                return envPath;
            }
        }
        return null;
    }

    private Map<String, Object> parseEnvFile(Path envFile) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(envFile)) {
                String trimmed = line.strip();

                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int eqIdx = trimmed.indexOf('=');
                if (eqIdx <= 0) {
                    continue;
                }

                String key = trimmed.substring(0, eqIdx).strip();
                String value = trimmed.substring(eqIdx + 1).strip();

                if (value.length() >= 2) {
                    char first = value.charAt(0);
                    char last = value.charAt(value.length() - 1);
                    if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                        value = value.substring(1, value.length() - 1);
                    }
                }

                result.put(key, value);
            }
        } catch (IOException e) {
            log.warn("[dotenv] Failed to read .env file: {}", e.getMessage());
        }
        return result;
    }
}
