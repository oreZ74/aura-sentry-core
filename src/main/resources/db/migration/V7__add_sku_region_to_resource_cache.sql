-- ═══════════════════════════════════════════════════════════════════
-- V7 – Add sku + region to resource_cache for frontend table parity
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE resource_cache
    ADD COLUMN sku    VARCHAR(255),
    ADD COLUMN region VARCHAR(255);
