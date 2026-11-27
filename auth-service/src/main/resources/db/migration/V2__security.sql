-- Account lockout, password change tracking, session metadata and a login history.
ALTER TABLE users ADD COLUMN failed_attempts     INT         NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until        TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ;

ALTER TABLE refresh_tokens ADD COLUMN user_agent VARCHAR(200);
ALTER TABLE refresh_tokens ADD COLUMN ip_address VARCHAR(64);

CREATE TABLE login_events (
    id         UUID PRIMARY KEY,
    user_id    UUID,
    username   VARCHAR(100) NOT NULL,
    success    BOOLEAN      NOT NULL,
    reason     VARCHAR(40),
    ip_address VARCHAR(64),
    user_agent VARCHAR(200),
    created_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_login_events_user_time ON login_events (user_id, created_at DESC);
