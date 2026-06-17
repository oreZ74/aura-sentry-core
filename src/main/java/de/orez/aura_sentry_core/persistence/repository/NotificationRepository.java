package de.orez.aura_sentry_core.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import de.orez.aura_sentry_core.persistence.entity.NotificationEntity;

/**
 * Repository for user-facing notifications.
 */
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    /**
     * Returns all unread notifications for the given user, newest first.
     */
    List<NotificationEntity> findByUserUsernameAndReadFalseOrderByCreatedAtDesc(String username);

    /**
     * Counts unread notifications for a user (used for the badge counter).
     */
    long countByUserUsernameAndReadFalse(String username);

    /**
     * Bulk-marks all unread notifications as read for a user.
     */
    @Modifying
    @Query("UPDATE NotificationEntity n SET n.read = true WHERE n.user.username = :username AND n.read = false")
    void markAllReadByUsername(String username);

    /**
     * Deletes all notifications for a user (used during account deletion).
     */
    @Modifying
    @Query("DELETE FROM NotificationEntity n WHERE n.user.username = :username")
    void deleteByUserUsername(String username);
}
