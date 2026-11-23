-- account_id / initiated_by / journal ids reference other services' data by value only (database per service).
CREATE TABLE payments (
    id                  UUID PRIMARY KEY,
    initiated_by        UUID           NOT NULL,
    idempotency_key     VARCHAR(100)   NOT NULL,
    account_id          UUID           NOT NULL,
    rail                VARCHAR(10)    NOT NULL,
    beneficiary_ref     VARCHAR(100)   NOT NULL,
    amount              NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3)     NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    failure_reason      VARCHAR(255),
    debit_journal_id    UUID,
    reversal_journal_id UUID,
    request_hash        VARCHAR(64)    NOT NULL,
    created_at          TIMESTAMPTZ    NOT NULL,
    updated_at          TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_payment_idempotency UNIQUE (initiated_by, idempotency_key)
);
-- the recovery job scans for payments still in flight
CREATE INDEX idx_payments_in_flight ON payments (created_at) WHERE status IN ('REQUESTED', 'DEBITED');

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
