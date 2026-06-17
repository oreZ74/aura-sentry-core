package de.orez.aura_sentry_core.persistence.initializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import de.orez.aura_sentry_core.config.PasswordResolver;
import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import de.orez.aura_sentry_core.persistence.repository.UserRepository;

/**
 * Optionally seeds a default user on first startup.
 *
 * <p>Only runs when {@code app.seed.user.email} is explicitly set in
 * {@code application.properties} (or via environment variable). If the
 * property is blank or absent, this initializer does nothing and the
 * system starts with an empty user table – users must register via the
 * {@code /register} UI.
 *
 * <p>The password is read from {@code app.password} and BCrypt-hashed.
 * If the seeded user already exists (matched by username), this
 * initializer skips to preserve existing data.
 */
@Component
@Order(5)
public class UserDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserDataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.seed.user.email:}")
    private String seedEmail;

    @Value("${app.seed.user.full-name:}")
    private String seedFullName;

    @Value("${app.seed.user.username:}")
    private String seedUsername;

    @Value("${app.password}")
    private String appPassword;

    public UserDataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedEmail == null || seedEmail.isBlank()) {
            log.info("[AuraSentry] No seed user configured (app.seed.user.email is blank) – skipping");
            return;
        }

        String username = seedUsername.isBlank() ? seedEmail.substring(0, seedEmail.indexOf('@')) : seedUsername;
        String fullName = seedFullName.isBlank() ? username : seedFullName;

        if (userRepository.existsByUsername(username)) {
            log.info("[AuraSentry] Seed user '{}' already exists – skipping", username);
            return;
        }

        log.info("[AuraSentry] Creating seed user '{}' ({})", username, seedEmail);

        String encodedPassword = PasswordResolver.resolvePassword(appPassword, passwordEncoder);

        UserEntity user = new UserEntity(username, encodedPassword, "ADMIN");
        user.setEnabled(true);
        user.setFullName(fullName);
        user.setEmail(seedEmail);

        UserEntity saved = userRepository.save(user);
        log.info("[AuraSentry] Seed user created with id={}", saved.getId());
    }
}
