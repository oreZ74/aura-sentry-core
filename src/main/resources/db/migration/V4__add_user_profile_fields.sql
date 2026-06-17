-- ═══════════════════════════════════════════════════════════════════
-- V4 – Add full_name and email columns to users table for the
--       Azure-style profile menu in the navbar.
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE users
    ADD COLUMN full_name VARCHAR(255),
    ADD COLUMN email     VARCHAR(255);
