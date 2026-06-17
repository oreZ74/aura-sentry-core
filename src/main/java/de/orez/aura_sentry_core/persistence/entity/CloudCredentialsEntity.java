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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Encrypted Azure and Gemini API credentials persisted in the database.
 * All secrets are stored AES-256-GCM encrypted at rest.
 *
 * <p>Each credential set belongs to exactly one {@link UserEntity}
 * ({@code @ManyToOne}). Queries always fetch the credential set for
 * the currently authenticated user.
 */
@Entity
@Table(name = "cloud_credentials")
public class CloudCredentialsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "tenant_id", nullable = false, length = 512)
    private String encryptedTenantId;

    @Column(name = "client_id", nullable = false, length = 512)
    private String encryptedClientId;

    @Column(name = "client_secret", nullable = false, length = 2048)
    private String encryptedClientSecret;

    @Column(name = "subscription_id", nullable = false, length = 512)
    private String encryptedSubscriptionId;

    @Column(name = "gemini_api_key", nullable = false, length = 2048)
    private String encryptedGeminiApiKey;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    public void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    // ── Getters ─────────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getEncryptedTenantId() {
        return encryptedTenantId;
    }

    public String getEncryptedClientId() {
        return encryptedClientId;
    }

    public String getEncryptedClientSecret() {
        return encryptedClientSecret;
    }

    public String getEncryptedSubscriptionId() {
        return encryptedSubscriptionId;
    }

    public String getEncryptedGeminiApiKey() {
        return encryptedGeminiApiKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UserEntity getUser() {
        return user;
    }

    // ── Setters ─────────────────────────────────────────────────────────────────

    public void setId(Long id) {
        this.id = id;
    }

    public void setEncryptedTenantId(String encryptedTenantId) {
        this.encryptedTenantId = encryptedTenantId;
    }

    public void setEncryptedClientId(String encryptedClientId) {
        this.encryptedClientId = encryptedClientId;
    }

    public void setEncryptedClientSecret(String encryptedClientSecret) {
        this.encryptedClientSecret = encryptedClientSecret;
    }

    public void setEncryptedSubscriptionId(String encryptedSubscriptionId) {
        this.encryptedSubscriptionId = encryptedSubscriptionId;
    }

    public void setEncryptedGeminiApiKey(String encryptedGeminiApiKey) {
        this.encryptedGeminiApiKey = encryptedGeminiApiKey;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setUser(UserEntity user) {
        this.user = user;
    }
}
