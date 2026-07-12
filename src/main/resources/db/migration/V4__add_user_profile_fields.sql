-- ═══════════════════════════════════════════════════════════════════
-- V4 – Add full_name and email columns to users table for the
--       Azure-style profile menu in the navbar.
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE users
ADD COLUMN IF NOT EXISTS full_name VARCHAR(255),
ADD COLUMN IF NOT EXISTS email VARCHAR(255);
