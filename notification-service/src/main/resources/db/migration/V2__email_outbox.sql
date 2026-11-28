-- Emails waiting to be sent. The row is written in the same transaction as the event that caused it; a dispatcher
-- sends it over SMTP and retries failures with backoff until max-attempts.
CREATE TABLE email_outbox (
    id              UUID PRIMARY KEY,
    event_id        UUID         NOT NULL,
    recipient       VARCHAR(254) NOT NULL,
    subject         VARCHAR(200) NOT NULL,
    body            TEXT         NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      VARCHAR(300),
    created_at      TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,
    UNIQUE (event_id, recipient, subject)
);
CREATE INDEX idx_email_outbox_due ON email_outbox (next_attempt_at) WHERE status = 'PENDING';
