package de.orez.aura_sentry_core.config;

import java.util.regex.Pattern;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Shared password resolution logic used by both the security configuration
 * and the user data initializer.
 *
 * <p>Handles BCrypt hash detection, Docker Compose {@code $$} escaping,
 * quote stripping, and plaintext-to-BCrypt encoding.
 */
public final class PasswordResolver {

    private static final Pattern BCRYPT_HASH_PATTERN =
            Pattern.compile("^\\$2[aby]\\$\\d{2}\\$.{53}$");

    private PasswordResolver() {}

    public static String resolvePassword(String rawPassword, PasswordEncoder encoder) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalStateException(
                    "APP_PASSWORD environment variable must not be empty. "
                            + "Set it to a BCrypt hash or a plaintext password.");
        }

        String candidate = stripSurroundingQuotes(rawPassword);

        if (BCRYPT_HASH_PATTERN.matcher(candidate).matches()) {
            return candidate;
        }

        String cleanedEscaping = candidate.replace("$$", "$");
        if (!cleanedEscaping.equals(candidate) && BCRYPT_HASH_PATTERN.matcher(cleanedEscaping).matches()) {
            return cleanedEscaping;
        }

        return encoder.encode(cleanedEscaping);
    }

    public static String stripSurroundingQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
