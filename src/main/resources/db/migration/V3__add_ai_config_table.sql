-- ═══════════════════════════════════════════════════════════════════
-- V3 – Add ai_config table for user-customizable AI prompt templates
-- ═══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS ai_config (
    id                    BIGSERIAL PRIMARY KEY,
    user_id               BIGINT NOT NULL UNIQUE REFERENCES users(id),
    system_template       TEXT,
    specific_instructions TEXT,
    updated_at            TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ai_config_user_id ON ai_config(user_id);
