package de.orez.aura_sentry_core.controller;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.orez.aura_sentry_core.persistence.entity.NotificationEntity;
import de.orez.aura_sentry_core.service.NotificationService;

/**
 * REST API for the frontend notification bell icon.
 *
 * <ul>
 *   <li>{@code GET  /api/notifications/unread}  – returns unread notifications for the current user</li>
 *   <li>{@code POST /api/notifications/mark-read} – marks all notifications as read</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    /**
     * Lightweight DTO returned by the REST endpoints – avoids serializing
     * the full {@link NotificationEntity} with its lazy-loaded user relation.
     */
    public record NotificationDto(Long id, String title, String message, Instant createdAt) {}

    // ── Endpoints ─────────────────────────────────────────────────────────────

    /**
     * Returns all unread notifications for the currently authenticated user,
     * newest first. Returns an empty list (HTTP 200) when there are none.
     *
     * <p>Called by the frontend on every page load to populate the bell-icon
     * badge and dropdown.
     */
    @GetMapping("/unread")
    public ResponseEntity<List<NotificationDto>> getUnread(Principal principal) {
        if (principal == null) {
            return ResponseEntity.ok(List.of());
        }

        List<NotificationDto> dtos = notificationService
                .getUnreadNotifications(principal.getName())
                .stream()
                .map(n -> new NotificationDto(n.getId(), n.getTitle(), n.getMessage(), n.getCreatedAt()))
                .toList();

        log.debug("[Notifications] GET /unread for '{}' → {} items",
                principal.getName(), dtos.size());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Marks all unread notifications as read for the currently authenticated
     * user. Returns HTTP 204 (No Content) on success.
     *
     * <p>Called by the frontend when the user clicks "Als gelesen markieren"
     * in the bell dropdown.
     */
    @PostMapping("/mark-read")
    public ResponseEntity<Void> markAllRead(Principal principal) {
        if (principal == null) {
            return ResponseEntity.noContent().build();
        }

        notificationService.markAllRead(principal.getName());
        log.info("[Notifications] POST /mark-read for '{}'", principal.getName());
        return ResponseEntity.noContent().build();
    }
}
