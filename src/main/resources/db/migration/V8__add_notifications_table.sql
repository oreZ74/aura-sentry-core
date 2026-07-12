-- ═══════════════════════════════════════════════════════════════════
-- V8 – Add notifications table for the bell-icon badge in the navbar.
--       Created when AI scanner or static engine detects IssueType
--       changes for a resource.
-- ═══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id),
    title VARCHAR(255) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notifications_user_id ON notifications (user_id);

CREATE INDEX IF NOT EXISTS idx_notifications_unread ON notifications (user_id, read);
