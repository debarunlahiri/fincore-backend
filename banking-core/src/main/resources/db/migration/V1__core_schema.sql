-- Accounts ----------------------------------------------------------------
CREATE SEQUENCE account_number_seq START WITH 1000000001;

-- owner_user_id is the auth-service user id, stored by value: ownership checks never need a remote call
CREATE TABLE accounts (
    id                   UUID PRIMARY KEY,
    account_number       VARCHAR(20)    NOT NULL UNIQUE,
    owner_user_id        UUID,
    type                 VARCHAR(20)    NOT NULL,
    currency             VARCHAR(3)     NOT NULL,
    balance              NUMERIC(19, 4) NOT NULL DEFAULT 0,
    status               VARCHAR(20)    NOT NULL,
    daily_transfer_limit NUMERIC(19, 4) NOT NULL,
    version              BIGINT         NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ    NOT NULL,
    -- last line of defence: customer accounts can never go negative, whatever the application does
    CONSTRAINT chk_balance_non_negative CHECK (type = 'SYSTEM' OR balance >= 0),
    CONSTRAINT chk_customer_account_has_owner CHECK (type = 'SYSTEM' OR owner_user_id IS NOT NULL)
);
CREATE INDEX idx_accounts_owner ON accounts (owner_user_id);

-- Settlement account that is the counter-party of every cash deposit / withdrawal / payment.
-- With balance = credits - debits it goes negative as customers deposit, so the sum of all account
-- balances is always zero (the double-entry invariant checked by reconciliation).
INSERT INTO accounts (id, account_number, owner_user_id, type, currency, balance, status, daily_transfer_limit, version, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'FCSYSTEM0001', NULL, 'SYSTEM', 'INR', 0, 'ACTIVE', 0, 0, now());

-- Ledger ------------------------------------------------------------------
CREATE TABLE journals (
    id          UUID PRIMARY KEY,
    type        VARCHAR(30)  NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE ledger_entries (
    id            UUID PRIMARY KEY,
    journal_id    UUID           NOT NULL REFERENCES journals (id),
    account_id    UUID           NOT NULL REFERENCES accounts (id),
    direction     VARCHAR(6)     NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount        NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency      VARCHAR(3)     NOT NULL,
    balance_after NUMERIC(19, 4) NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_ledger_account_time ON ledger_entries (account_id, created_at DESC, id);
CREATE INDEX idx_ledger_journal ON ledger_entries (journal_id);

-- The ledger is append-only: postings are corrected by new reversing postings, never edited.
CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'ledger is append-only (% on %)', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

CREATE TRIGGER journals_append_only
    BEFORE UPDATE OR DELETE ON journals
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

-- Idempotency ledger for postings requested by other services (payment Saga). No FK to journals on purpose:
-- the row is inserted with plain JDBC before Hibernate flushes the journal in the same transaction.
CREATE TABLE external_postings (
    reference  VARCHAR(100)   PRIMARY KEY,
    kind       VARCHAR(20)    NOT NULL,
    account_id UUID           NOT NULL,
    amount     NUMERIC(19, 4) NOT NULL,
    journal_id UUID           NOT NULL,
    created_at TIMESTAMPTZ    NOT NULL
);

-- Transfers ---------------------------------------------------------------
CREATE TABLE transfers (
    id                UUID PRIMARY KEY,
    idempotency_key   VARCHAR(100)   NOT NULL,
    initiated_by      UUID           NOT NULL,
    source_account_id UUID           NOT NULL REFERENCES accounts (id),
    target_account_id UUID           NOT NULL REFERENCES accounts (id),
    amount            NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency          VARCHAR(3)     NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    remarks           VARCHAR(140),
    request_hash      VARCHAR(64)    NOT NULL,
    journal_id        UUID           NOT NULL REFERENCES journals (id),
    created_at        TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_transfer_idempotency UNIQUE (initiated_by, idempotency_key),
    CONSTRAINT chk_distinct_accounts CHECK (source_account_id <> target_account_id)
);
CREATE INDEX idx_transfers_source_time ON transfers (source_account_id, created_at DESC);

-- Transactional outbox ----------------------------------------------------
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

-- Audit trail -------------------------------------------------------------
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
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();
