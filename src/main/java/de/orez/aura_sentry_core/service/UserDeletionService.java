package de.orez.aura_sentry_core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.orez.aura_sentry_core.persistence.entity.AiConfigEntity;
import de.orez.aura_sentry_core.persistence.entity.CloudCredentialsEntity;
import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import de.orez.aura_sentry_core.persistence.repository.AiConfigRepository;
import de.orez.aura_sentry_core.persistence.repository.CloudCredentialsRepository;
import de.orez.aura_sentry_core.persistence.repository.NotificationRepository;
import de.orez.aura_sentry_core.persistence.repository.UserRepository;

/**
 * Handles permanent account deletion with explicit cascading removal
 * of all associated entities ({@link AiConfigEntity},
 * {@link CloudCredentialsEntity}, notifications).
 *
 * <p>Explicit deletion is used instead of JPA {@code cascade} /
 * {@code orphanRemoval} because {@link UserEntity} does not own the
 * relationship mappings – all {@code @ManyToOne} / {@code @OneToOne}
 * annotations are on the child side. A direct
 * {@code userRepository.delete(user)} would throw a
 * {@link org.springframework.dao.DataIntegrityViolationException}
 * due to foreign-key constraints.
 */
@Service
public class UserDeletionService {

    private static final Logger log = LoggerFactory.getLogger(UserDeletionService.class);

    private final UserRepository userRepository;
    private final AiConfigRepository aiConfigRepository;
    private final CloudCredentialsRepository credentialsRepository;
    private final NotificationRepository notificationRepository;

    public UserDeletionService(UserRepository userRepository,
                               AiConfigRepository aiConfigRepository,
                               CloudCredentialsRepository credentialsRepository,
                               NotificationRepository notificationRepository) {
        this.userRepository = userRepository;
        this.aiConfigRepository = aiConfigRepository;
        this.credentialsRepository = credentialsRepository;
        this.notificationRepository = notificationRepository;
    }

    /**
     * Permanently deletes the user and all associated data.
     *
     * <p>Deletion order matters: child rows must be removed before the
     * parent row to satisfy foreign-key constraints. Each step is
     * executed inside a single {@code @Transactional} boundary so that
     * a failure at any point rolls back the entire operation.
     *
     * @param username the username of the account to delete
     * @throws IllegalArgumentException if no user exists with the given username
     */
    @Transactional
    public void deleteAccount(String username) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException(
                        "User not found: " + username));

        log.warn("Deleting account and all associated data for user '{}' (id={})",
                username, user.getId());

        notificationRepository.deleteByUserUsername(username);

        credentialsRepository.findTopByUserOrderByUpdatedAtDesc(user)
                .ifPresent(creds -> {
                    credentialsRepository.delete(creds);
                    credentialsRepository.flush();
                });

        aiConfigRepository.findByUserUsername(username)
                .ifPresent(aiConfig -> aiConfigRepository.delete(aiConfig));

        userRepository.delete(user);
        userRepository.flush();

        log.info("Account '{}' permanently deleted", username);
    }
}
