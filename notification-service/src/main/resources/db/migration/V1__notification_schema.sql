-- Consumer-side deduplication: the consumer records (consumer, event_id) in the same transaction as its side
-- effect, so a redelivered Kafka message is detected and skipped.
CREATE TABLE processed_events (
    consumer     VARCHAR(80) NOT NULL,
    event_id     UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

-- Simulated notifications (email/SMS are only recorded and logged). account_id is the banking-core account id,
-- referenced by value only.
CREATE TABLE notifications (
    id         UUID PRIMARY KEY,
    event_id   UUID         NOT NULL,
    account_id UUID         NOT NULL,
    channel    VARCHAR(10)  NOT NULL,
    message    VARCHAR(300) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_notifications_account ON notifications (account_id, created_at DESC);

-- common-data's outbox and audit entities are mapped in every service that scans it, so their tables must exist
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(80)  NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE status = 'PENDING';

CREATE FUNCTION forbid_mutation() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'append-only table (% on %)', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE audit_log (
    id          UUID PRIMARY KEY,
    actor       VARCHAR(100) NOT NULL,
    action      VARCHAR(60)  NOT NULL,
    entity_type VARCHAR(40),
    entity_id   VARCHAR(64),
    details     VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_audit_time ON audit_log (created_at DESC);
CREATE INDEX idx_audit_actor ON audit_log (actor, created_at DESC);
CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
