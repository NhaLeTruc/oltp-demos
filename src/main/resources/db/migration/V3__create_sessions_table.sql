-- V3__create_sessions_table.sql
-- Description: Create sessions table for connection pooling demonstrations
-- Author: OLTP Demo Team
-- Date: 2025-11-16

-- ============================================================================
-- Table: sessions
-- Purpose: Tracks active user sessions (for connection pooling demos)
-- ============================================================================

CREATE TABLE sessions (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    session_token       UUID NOT NULL UNIQUE,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at          TIMESTAMP NOT NULL,
    last_accessed_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_sessions_expires_after_created CHECK (expires_at > created_at)
);

COMMENT ON TABLE sessions IS 'Active user sessions - used for connection pooling demonstrations';
COMMENT ON COLUMN sessions.session_token IS 'Unique UUID session identifier';
COMMENT ON COLUMN sessions.expires_at IS 'Session expiration timestamp';
COMMENT ON COLUMN sessions.last_accessed_at IS 'Last activity timestamp for session management';

-- Indexes for sessions
CREATE INDEX idx_sessions_user_id ON sessions(user_id);
CREATE INDEX idx_sessions_session_token ON sessions(session_token);
CREATE INDEX idx_sessions_expires_at ON sessions(expires_at);  -- For cleanup queries
CREATE INDEX idx_sessions_last_accessed ON sessions(last_accessed_at);

-- ============================================================================
-- Rollback Instructions (for reference)
-- ============================================================================
-- DROP TABLE IF EXISTS sessions CASCADE;
