-- ═══════════════════════════════════════════════════════════════════
-- V5 – Add ai_locked flag to scan_results table.
--       When the AI sets a flag, the static scanner must not overwrite it.
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE scan_results
    ADD COLUMN ai_locked BOOLEAN NOT NULL DEFAULT FALSE;
