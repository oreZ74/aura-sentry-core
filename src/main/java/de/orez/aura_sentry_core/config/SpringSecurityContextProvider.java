package de.orez.aura_sentry_core.config;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import de.orez.aura_sentry_core.persistence.repository.UserRepository;

@Component
public class SpringSecurityContextProvider implements SecurityContextProvider {

    private final UserRepository userRepository;

    public SpringSecurityContextProvider(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserEntity getCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalStateException("No authenticated user – cannot load credentials");
        }
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user '" + auth.getName() + "' not found in database"));
    }
}
