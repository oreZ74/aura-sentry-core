package de.orez.aura_sentry_core.persistence.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * AI configuration for a user – stores customizable prompt templates
 * and specific instructions for the Gemini-powered remediation advisor.
 *
 * <p>Stored in a separate table (not encrypted) because prompt templates
 * are not secrets – they are user-customizable configuration.
 *
 * <p>Each user has exactly one {@code AiConfigEntity} ({@code @OneToOne}).
 * If none exists, the {@link RemediationAgent} falls back to a built-in
 * default system prompt.
 */
@Entity
@Table(name = "ai_config")
public class AiConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private UserEntity user;

    @Column(name = "system_template", length = 4000)
    private String systemTemplate;

    @Column(name = "specific_instructions", length = 4000)
    private String specificInstructions;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    public void onPersist() {
        updatedAt = Instant.now();
    }

    // ── Getters ─────────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public UserEntity getUser() {
        return user;
    }

    public String getSystemTemplate() {
        return systemTemplate;
    }

    public String getSpecificInstructions() {
        return specificInstructions;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // ── Setters ─────────────────────────────────────────────────────────────────

    public void setId(Long id) {
        this.id = id;
    }

    public void setUser(UserEntity user) {
        this.user = user;
    }

    public void setSystemTemplate(String systemTemplate) {
        this.systemTemplate = systemTemplate;
    }

    public void setSpecificInstructions(String specificInstructions) {
        this.specificInstructions = specificInstructions;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
