package de.orez.aura_sentry_core.persistence.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Persisted notification for a single user.
 *
 * <p>Created whenever the AI scanner or the static scan engine detects
 * an {@code IssueType} change for a resource. The frontend polls
 * {@code GET /api/notifications/unread} to display the bell-icon badge.
 */
@Entity
@Table(name = "notifications")
public class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 1000)
    private String message;

    @Column(nullable = false)
    private boolean read = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // ── Constructors ──────────────────────────────────────────────────────────

    protected NotificationEntity() {}

    public NotificationEntity(UserEntity user, String title, String message) {
        this.user = user;
        this.title = title;
        this.message = message;
        this.createdAt = Instant.now();
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public Long getId()              { return id; }
    public UserEntity getUser()      { return user; }
    public String getTitle()         { return title; }
    public String getMessage()       { return message; }
    public boolean isRead()          { return read; }
    public Instant getCreatedAt()    { return createdAt; }

    public void setRead(boolean read) { this.read = read; }
}
