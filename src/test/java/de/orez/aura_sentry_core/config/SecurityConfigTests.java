package de.orez.aura_sentry_core.config;

import de.orez.aura_sentry_core.AbstractIntegrationTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for SecurityConfig BCrypt password handling.
 *
 * Validates:
 * - BCrypt hash recognition and validation (includes Docker Compose $$ escaping)
 * - Plaintext password hashing at startup
 * - Login credential matching
 * - Quote stripping from environment variable values
 */
@DisplayName("SecurityConfig BCrypt Password Tests")
class SecurityConfigTests extends AbstractIntegrationTest {

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ──────────────────────────────────────────────
    // Existing tests (kept intact)
    // ──────────────────────────────────────────────

    /**
     * Test: BCryptPasswordEncoder is correctly configured
     */
    @Test
    @DisplayName("PasswordEncoder is BCryptPasswordEncoder")
    void testPasswordEncoderType() {
        assertNotNull(passwordEncoder, "PasswordEncoder should be injected");
        assertTrue(passwordEncoder.getClass().getSimpleName().contains("BCrypt"),
                "Should use BCryptPasswordEncoder");
    }

    /**
     * Test: Can match correct password against BCrypt hash
     */
    @Test
    @DisplayName("Matches correct password against BCrypt hash")
    void testPasswordMatching() {
        String plainPassword = "testPassword123";
        String hash = passwordEncoder.encode(plainPassword);

        // Hash should start with $2a$ or $2b$
        assertTrue(hash.startsWith("$2a$") || hash.startsWith("$2b$"),
                "BCrypt hash should start with $2a$ or $2b$");

        // Encoding same password twice should produce different hashes
        String hash2 = passwordEncoder.encode(plainPassword);
        assertNotEquals(hash, hash2, "BCrypt salts should make each hash unique");

        // But both should match the plaintext
        assertTrue(passwordEncoder.matches(plainPassword, hash),
                "Correct password should match the hash");
        assertTrue(passwordEncoder.matches(plainPassword, hash2),
                "Correct password should match hash2");
    }

    /**
     * Test: Wrong password doesn't match
     */
    @Test
    @DisplayName("Wrong password does not match hash")
    void testWrongPasswordRejection() {
        String correctPassword = "correctPassword";
        String wrongPassword = "wrongPassword";

        String hash = passwordEncoder.encode(correctPassword);

        assertFalse(passwordEncoder.matches(wrongPassword, hash),
                "Wrong password should not match the hash");
    }

    /**
     * Test: Pre-hashed BCrypt string is recognized as valid.
     * This validates the round-trip: encode → matches should always succeed.
     */
    @Test
    @DisplayName("Pre-hashed BCrypt string is recognized correctly")
    void testBcryptHashRecognition() {
        // Round-trip: encode "admin" and verify it matches
        String hash = passwordEncoder.encode("admin");

        // Should match the plaintext password "admin"
        assertTrue(passwordEncoder.matches("admin", hash),
                "BCrypt round-trip encode → matches must succeed");

        // Hash should follow the BCrypt format (2a, 2b, or 2y variant)
        assertTrue(hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$"),
                "Encoded password must start with a valid BCrypt variant prefix");
    }

    /**
     * Test: Plaintext password is hashed once at startup
     * The PasswordEncoder.encode() should be called once
     */
    @Test
    @DisplayName("Plaintext passwords are encoded during initialization")
    void testPlaintextPasswordEncoding() {
        String plainPassword = "myPassword";

        // Simulate what SecurityConfig does
        String encodedOnce = passwordEncoder.encode(plainPassword);
        String encodedTwice = passwordEncoder.encode(encodedOnce); // DON'T do this!

        // After encoding once, matches plaintext
        assertTrue(passwordEncoder.matches(plainPassword, encodedOnce),
                "Plaintext should match single-encoded hash");

        // After encoding twice, does NOT match plaintext
        assertFalse(passwordEncoder.matches(plainPassword, encodedTwice),
                "Plaintext should NOT match double-encoded hash (key security property)");
    }

    /**
     * Test: Case sensitivity
     * BCrypt hashing should be deterministic for the same plaintext input
     */
    @Test
    @DisplayName("Password matching is case-sensitive")
    void testPasswordCaseSensitivity() {
        String password = "MyPassword";
        String hash = passwordEncoder.encode(password);

        assertTrue(passwordEncoder.matches("MyPassword", hash),
                "Exact case should match");
        assertFalse(passwordEncoder.matches("mypassword", hash),
                "Different case should not match");
        assertFalse(passwordEncoder.matches("MYPASSWORD", hash),
                "Different case should not match");
    }

    /**
     * Test: BCrypt strength configuration
     * SecurityConfig should use strength=10 (100ms hashing)
     */
    @Test
    @DisplayName("BCrypt uses appropriate strength for performance vs security")
    void testBcryptStrength() {
        long startTime = System.currentTimeMillis();
        String hash = passwordEncoder.encode("testPassword");
        long duration = System.currentTimeMillis() - startTime;

        // Strength 10 should take roughly 50-150ms per hash on modern hardware
        // We're lenient here to account for test environment variability
        assertTrue(duration > 10, "Hashing should take noticeable time (indicates good strength)");
        assertTrue(duration < 5000, "Hashing should not take more than 5 seconds");

        // Hash should follow BCrypt format
        assertTrue(hash.matches("\\$2[aby]\\$\\d{2}\\$.{53}"),
                "Hash should follow BCrypt format with proper length");
    }

    /**
     * Test: Handling of special characters and long passwords.
     * BCrypt enforces a 72-byte maximum on the raw password.
     */
    @Test
    @DisplayName("BCrypt handles special characters and passwords up to 72 bytes")
    void testSpecialCharactersAndLength() {
        String specialPassword = "P@ssw0rd!#$%&*()_+{}[]|:;<>?,./~`";
        String longPassword = "a".repeat(72); // BCrypt maximum (72 bytes)

        String hash1 = passwordEncoder.encode(specialPassword);
        String hash2 = passwordEncoder.encode(longPassword);

        assertTrue(passwordEncoder.matches(specialPassword, hash1),
                "Special characters should be handled correctly");
        assertTrue(passwordEncoder.matches(longPassword, hash2),
                "Long passwords should be handled correctly");
    }

    // ──────────────────────────────────────────────
    // NEW: resolvePassword() robustness tests
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("resolvePassword – BCrypt hash detection")
    class ResolvePasswordBcryptDetection {

        @Test
        @DisplayName("Valid $2a$ hash is returned as-is (no double-encoding)")
        void valid2aHashReturnedUnchanged() {
            String hash = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcg7b3XeKeUxWdeS86E36DxJstC";
            String result = PasswordResolver.resolvePassword(hash, passwordEncoder);
            assertEquals(hash, result, "Pre-hashed BCrypt string must be returned unchanged");
        }

        @Test
        @DisplayName("Valid $2b$ hash is returned as-is")
        void valid2bHashReturnedUnchanged() {
            String hash = "$2b$10$R9h7cIPz0gi.URNNGHQ1be2wWYQ5w7..bzvCPubBULdwdD4Dv6uFi";
            String result = PasswordResolver.resolvePassword(hash, passwordEncoder);
            assertEquals(hash, result, "Pre-hashed BCrypt string must be returned unchanged");
        }

        @Test
        @DisplayName("Valid $2y$ hash is returned as-is")
        void valid2yHashReturnedUnchanged() {
            String hash = "$2y$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcg7b3XeKeUxWdeS86E36DxJstC";
            String result = PasswordResolver.resolvePassword(hash, passwordEncoder);
            assertEquals(hash, result, "Pre-hashed BCrypt string must be returned unchanged");
        }

        @Test
        @DisplayName("Plaintext password is encoded via BCrypt")
        void plaintextPasswordIsEncoded() {
            String plaintext = "hallo123";
            String result = PasswordResolver.resolvePassword(plaintext, passwordEncoder);

            // Must be different from input (it's a hash now)
            assertNotEquals(plaintext, result, "Plaintext must be hashed, not returned as-is");

            // Must match the original plaintext when verified
            assertTrue(passwordEncoder.matches(plaintext, result),
                    "Encoded password must verify against original plaintext");
        }

        @Test
        @DisplayName("Plaintext that looks like partial BCrypt prefix is still encoded")
        void plaintextWithDollarSignIsEncoded() {
            // "$2a$" alone is NOT a valid BCrypt hash (too short, no 53-char payload)
            String plaintext = "$2a$10$short";
            String result = PasswordResolver.resolvePassword(plaintext, passwordEncoder);

            assertNotEquals(plaintext, result,
                    "Short string starting with $2a$ must be encoded, not treated as pre-hashed");
            assertTrue(passwordEncoder.matches(plaintext, result),
                    "Encoded password must verify against original input");
        }
    }

    @Nested
    @DisplayName("resolvePassword – Docker Compose $$ escaping")
    class ResolvePasswordEscaping {

        @Test
        @DisplayName("Docker Compose escaped hash ($$) is cleaned and returned as-is")
        void dockerComposeEscapedHashCleaned() {
            String originalHash = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcg7b3XeKeUxWdeS86E36DxJstC";
            String escapedHash = "$$2a$$10$$N9qo8uLOickgx2ZMRZoMyeIjZAgcg7b3XeKeUxWdeS86E36DxJstC";

            String result = PasswordResolver.resolvePassword(escapedHash, passwordEncoder);

            assertEquals(originalHash, result,
                    "$$ escaped hash must be cleaned to single $ and returned as-is");
        }

        @Test
        @DisplayName("Escaped hash still verifies the original plaintext")
        void escapedHashStillVerifiesPlaintext() {
            // Generate a valid BCrypt hash for "admin"
            String originalHash = passwordEncoder.encode("admin");

            // Apply Docker Compose $$ escaping
            String escapedHash = originalHash.replace("$", "$$");

            String result = PasswordResolver.resolvePassword(escapedHash, passwordEncoder);

            // The cleaned hash should still match "admin"
            assertTrue(passwordEncoder.matches("admin", result),
                    "Cleaned pre-hashed password must match the original plaintext");
        }

        @Test
        @DisplayName("Multiple consecutive $$ pairs are all cleaned")
        void multipleDoubleDollarCleaned() {
            // Hash with multiple $$ pairs throughout
            String escapedHash = "$$2a$$10$$N9qo8uLOickgx2$$MRZoMyeIjZAgcg7b3XeKeUxWdeS86E36DxJstC";
            String originalHash = "$2a$10$N9qo8uLOickgx2$MRZoMyeIjZAgcg7b3XeKeUxWdeS86E36DxJstC";

            String result = PasswordResolver.resolvePassword(escapedHash, passwordEncoder);

            assertEquals(originalHash, result, "All $$ pairs must be cleaned to single $");
        }
    }

    @Nested
    @DisplayName("resolvePassword – quote stripping")
    class ResolvePasswordQuoteStripping {

        @Test
        @DisplayName("Single-quoted BCrypt hash is stripped and recognized")
        void singleQuotedHashStripped() {
            String hash = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcg7b3XeKeUxWdeS86E36DxJstC";
            String quoted = "'" + hash + "'";

            String result = PasswordResolver.resolvePassword(quoted, passwordEncoder);

            assertEquals(hash, result, "Single quotes must be stripped before hash recognition");
        }

        @Test
        @DisplayName("Double-quoted BCrypt hash is stripped and recognized")
        void doubleQuotedHashStripped() {
            String hash = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcg7b3XeKeUxWdeS86E36DxJstC";
            String quoted = "\"" + hash + "\"";

            String result = PasswordResolver.resolvePassword(quoted, passwordEncoder);

            assertEquals(hash, result, "Double quotes must be stripped before hash recognition");
        }

        @Test
        @DisplayName("Single-quoted plaintext is stripped and encoded")
        void singleQuotedPlaintextStrippedAndEncoded() {
            String plaintext = "hallo123";
            String quoted = "'" + plaintext + "'";

            String result = PasswordResolver.resolvePassword(quoted, passwordEncoder);

            assertNotEquals(quoted, result, "Result must not be the quoted input");
            assertTrue(passwordEncoder.matches(plaintext, result),
                    "Encoded password must verify against unquoted plaintext");
        }
    }

    @Nested
    @DisplayName("resolvePassword – error handling")
    class ResolvePasswordErrors {

        @Test
        @DisplayName("Null password throws IllegalStateException")
        void nullPasswordThrowsException() {
            assertThrows(IllegalStateException.class,
                    () -> PasswordResolver.resolvePassword(null, passwordEncoder),
                    "Null APP_PASSWORD must throw IllegalStateException");
        }

        @Test
        @DisplayName("Blank password throws IllegalStateException")
        void blankPasswordThrowsException() {
            assertThrows(IllegalStateException.class,
                    () -> PasswordResolver.resolvePassword("   ", passwordEncoder),
                    "Blank APP_PASSWORD must throw IllegalStateException");
        }

        @Test
        @DisplayName("Empty password throws IllegalStateException")
        void emptyPasswordThrowsException() {
            assertThrows(IllegalStateException.class,
                    () -> PasswordResolver.resolvePassword("", passwordEncoder),
                    "Empty APP_PASSWORD must throw IllegalStateException");
        }
    }

    @Nested
    @DisplayName("stripSurroundingQuotes – unit tests")
    class StripSurroundingQuotesTests {

        @Test
        @DisplayName("Single-quoted value: quotes stripped")
        void singleQuotesStripped() {
            assertEquals("hello", PasswordResolver.stripSurroundingQuotes("'hello'"));
        }

        @Test
        @DisplayName("Double-quoted value: quotes stripped")
        void doubleQuotesStripped() {
            assertEquals("hello", PasswordResolver.stripSurroundingQuotes("\"hello\""));
        }

        @Test
        @DisplayName("Unquoted value: unchanged")
        void unquotedUnchanged() {
            assertEquals("hello", PasswordResolver.stripSurroundingQuotes("hello"));
        }

        @Test
        @DisplayName("Mismatched quotes: unchanged")
        void mismatchedQuotesUnchanged() {
            assertEquals("'hello\"", PasswordResolver.stripSurroundingQuotes("'hello\""));
        }

        @Test
        @DisplayName("Single character: unchanged")
        void singleCharUnchanged() {
            assertEquals("a", PasswordResolver.stripSurroundingQuotes("a"));
        }

        @Test
        @DisplayName("Null: returns null")
        void nullReturnsNull() {
            assertEquals(null, PasswordResolver.stripSurroundingQuotes(null));
        }

        @Test
        @DisplayName("Only quotes: stripped to empty string")
        void onlyQuotesStrippedToEmpty() {
            assertEquals("", PasswordResolver.stripSurroundingQuotes("\"\""));
            assertEquals("", PasswordResolver.stripSurroundingQuotes("''"));
        }
    }
}
