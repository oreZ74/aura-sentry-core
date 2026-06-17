package de.orez.aura_sentry_core.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.orez.aura_sentry_core.persistence.entity.NotificationEntity;
import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import de.orez.aura_sentry_core.persistence.repository.NotificationRepository;
import de.orez.aura_sentry_core.persistence.repository.UserRepository;

/**
 * Service for creating and querying user notifications.
 *
 * <p>Called by the {@code ScanPersistenceService} whenever the AI scanner
 * or the static scan engine detects an {@code IssueType} transition for a
 * resource. The notification is persisted in the database and picked up by
 * the frontend via {@code GET /api/notifications/unread}.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository notificationRepository,
                               UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    /**
     * Creates a status-change notification for the given user.
     *
     * <p>If the user does not exist in the database (e.g. during background
     * scans where the user context may be ambiguous), the notification is
     * silently dropped and a warning is logged.
     *
     * @param username     the target user's username (Spring Security principal name)
     * @param resourceName human-readable resource name
     * @param oldStatus    the previous {@code IssueType} value
     * @param newStatus    the new {@code IssueType} value
     */
    @Transactional
    public void createNotification(String username, String resourceName,
                                   String oldStatus, String newStatus) {
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            log.warn("[Notifications] Could not find user '{}' – notification dropped for '{}'",
                    username, resourceName);
            return;
        }

        String title = "Status-Änderung erkannt";
        String message = "Status für '%s' hat sich von %s auf %s geändert."
                .formatted(resourceName, oldStatus, newStatus);

        var notification = new NotificationEntity(user, title, message);
        notificationRepository.save(notification);

        log.info("[Notifications] Created notification for user '{}': {} → {} ({})",
                username, oldStatus, newStatus, resourceName);
    }

    /**
     * Returns all unread notifications for the given user, newest first.
     *
     * @param username the user's username
     * @return list of unread {@link NotificationEntity} objects
     */
    @Transactional(readOnly = true)
    public List<NotificationEntity> getUnreadNotifications(String username) {
        return notificationRepository.findByUserUsernameAndReadFalseOrderByCreatedAtDesc(username);
    }

    /**
     * Returns the count of unread notifications for the given user.
     *
     * @param username the user's username
     * @return number of unread notifications
     */
    @Transactional(readOnly = true)
    public long countUnread(String username) {
        return notificationRepository.countByUserUsernameAndReadFalse(username);
    }

    /**
     * Marks all unread notifications as read for the given user.
     *
     * @param username the user's username
     */
    @Transactional
    public void markAllRead(String username) {
        notificationRepository.markAllReadByUsername(username);
        log.info("[Notifications] Marked all notifications as read for user '{}'", username);
    }
}
