package de.orez.aura_sentry_core.persistence.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Application user entity.
 *
 * <p>Each user can have their own set of cloud credentials
 * (Azure + Gemini API keys) linked via {@code @ManyToOne} in
 * {@link CloudCredentialsEntity}.
 */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(nullable = false, length = 20)
    private String role = "USER";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "full_name")
    private String fullName;

    @Column
    private String email;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    public void onPersist() {
        createdAt = Instant.now();
    }

    // ── Constructors ──────────────────────────────────────────────────────────

    protected UserEntity() {}

    public UserEntity(String username, String password, String role) {
        this.username = username;
        this.password = password;
        this.role = role;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }

    /**
     * Extracts uppercase initials from the user's full name.
     * <p>Examples: "Emre Akarsu" → "EA", "Max" → "MA", null → "??".
     */
    @jakarta.persistence.Transient
    public String getInitials() {
        if (fullName == null || fullName.isBlank()) {
            return username != null ? username.substring(0, Math.min(2, username.length())).toUpperCase() : "??";
        }
        String trimmed = fullName.trim().replaceAll("\\s+", " ");
        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace == -1) {
            return trimmed.substring(0, Math.min(2, trimmed.length())).toUpperCase();
        }
        return (trimmed.charAt(0) + "" + trimmed.charAt(firstSpace + 1)).toUpperCase();
    }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setId(Long id) { this.id = id; }
    public void setUsername(String username) { this.username = username; }
    public void setPassword(String password) { this.password = password; }
    public void setRole(String role) { this.role = role; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public void setEmail(String email) { this.email = email; }
}
