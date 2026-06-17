package de.orez.aura_sentry_core.persistence.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import de.orez.aura_sentry_core.persistence.entity.AiConfigEntity;

/**
 * Repository for AI configuration (prompt templates and specific instructions).
 */
public interface AiConfigRepository extends JpaRepository<AiConfigEntity, Long> {

    Optional<AiConfigEntity> findByUserUsername(String username);

    boolean existsByUserUsername(String username);
}
