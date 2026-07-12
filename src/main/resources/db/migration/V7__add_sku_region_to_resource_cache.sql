-- ═══════════════════════════════════════════════════════════════════
-- V7 – Add sku + region to resource_cache for frontend table parity
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE resource_cache
ADD COLUMN IF NOT EXISTS sku VARCHAR(255),
ADD COLUMN IF NOT EXISTS region VARCHAR(255);
