-- ═══════════════════════════════════════════════════════════════════
-- V1 – Initial schema: tables matching the three @Entity classes
--       de.orez.aura_sentry_core.persistence.entity.*
-- ═══════════════════════════════════════════════════════════════════

-- Required for Hibernate's @GeneratedValue(strategy = GenerationType.UUID)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ───────────────────────────────────────────────────────────────────
-- cloud_credentials – encrypted Azure & Gemini API credentials
-- (explicit @Column names, independent of naming strategy)
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE cloud_credentials (
    id               BIGSERIAL    PRIMARY KEY,
    tenant_id        VARCHAR(512)  NOT NULL,
    client_id        VARCHAR(512)  NOT NULL,
    client_secret    VARCHAR(2048) NOT NULL,
    subscription_id  VARCHAR(512)  NOT NULL,
    gemini_api_key   VARCHAR(2048) NOT NULL,
    created_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ
);

-- ───────────────────────────────────────────────────────────────────
-- scan_results – persisted Azure resource scan snapshots
-- (SpringPhysicalNamingStrategy converts camelCase → snake_case)
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE scan_results (
    id             UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    scanned_at     TIMESTAMPTZ  NOT NULL,
    resource_id    VARCHAR(255) NOT NULL,
    resource_name  VARCHAR(255) NOT NULL,
    resource_type  VARCHAR(255),
    resource_group VARCHAR(255),
    state          VARCHAR(255)
);

-- ───────────────────────────────────────────────────────────────────
-- scan_findings – individual optimisation flags per scan
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE scan_findings (
    id             UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    flag           VARCHAR(255) NOT NULL,
    reason         VARCHAR(1000) NOT NULL,
    scan_result_id UUID         NOT NULL
        REFERENCES scan_results(id)
        ON DELETE CASCADE
);

-- ───────────────────────────────────────────────────────────────────
-- Indexes for common query patterns
-- ───────────────────────────────────────────────────────────────────
CREATE INDEX idx_scan_results_scanned_at ON scan_results(scanned_at DESC);
CREATE INDEX idx_scan_findings_result_id  ON scan_findings(scan_result_id);
CREATE INDEX idx_cloud_creds_updated_at   ON cloud_credentials(updated_at DESC);
