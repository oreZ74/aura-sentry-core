package de.orez.aura_sentry_core.persistence.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import de.orez.aura_sentry_core.persistence.entity.CloudCredentialsEntity;
import de.orez.aura_sentry_core.persistence.entity.UserEntity;

/**
 * Repository for encrypted cloud credentials.
 * Queries are scoped to the authenticated user.
 */
public interface CloudCredentialsRepository extends JpaRepository<CloudCredentialsEntity, Long> {

    Optional<CloudCredentialsEntity> findTopByUserOrderByUpdatedAtDesc(UserEntity user);
}
