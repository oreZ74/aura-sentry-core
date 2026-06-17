-- ═══════════════════════════════════════════════════════════════════
-- V2 – User-scoped credentials: adds users table and links
--       cloud_credentials to a specific user via foreign key.
-- ═══════════════════════════════════════════════════════════════════

-- ───────────────────────────────────────────────────────────────────
-- users – application user accounts
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE users (
    id         BIGSERIAL    PRIMARY KEY,
    username   VARCHAR(100) NOT NULL UNIQUE,
    password   VARCHAR(255) NOT NULL,
    role       VARCHAR(20)  NOT NULL DEFAULT 'ADMIN',
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ
);

-- ───────────────────────────────────────────────────────────────────
-- cloud_credentials – add user_id foreign key
-- ───────────────────────────────────────────────────────────────────
ALTER TABLE cloud_credentials
    ADD COLUMN user_id BIGINT NOT NULL REFERENCES users(id);

CREATE INDEX idx_cloud_creds_user_id ON cloud_credentials(user_id);

-- ───────────────────────────────────────────────────────────────────
-- Seed the initial admin user (password from APP_PASSWORD env var
-- will be hashed by SecurityConfig at startup).
-- The CredentialDataInitializer will assign credentials to this user.
-- ───────────────────────────────────────────────────────────────────
INSERT INTO users (username, password, role, enabled, created_at)
VALUES ('admin', '<<will-be-set-by-app>>', 'ADMIN', TRUE, NOW())
ON CONFLICT (username) DO NOTHING;
