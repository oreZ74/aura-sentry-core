-- ═══════════════════════════════════════════════════════════════════
-- V6 – Add resource_cache table for live resource state with
--       eager cost refresh and upsert semantics.
-- ═══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS resource_cache (
    id              BIGSERIAL       PRIMARY KEY,
    azure_id        VARCHAR(512)    NOT NULL UNIQUE,
    name            VARCHAR(255)    NOT NULL,
    type            VARCHAR(255),
    resource_group  VARCHAR(255),
    state           VARCHAR(50),
    flag            VARCHAR(50),
    cost            DOUBLE PRECISION,
    currency        VARCHAR(10)     DEFAULT 'USD',
    scanned_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_resource_cache_azure_id   ON resource_cache(azure_id);
CREATE INDEX IF NOT EXISTS idx_resource_cache_scanned_at ON resource_cache(scanned_at DESC);
CREATE INDEX IF NOT EXISTS idx_resource_cache_flag       ON resource_cache(flag);
