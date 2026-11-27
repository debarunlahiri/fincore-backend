-- Beneficiaries, one-time passwords, fixed deposits, loans, cards.

CREATE TABLE beneficiaries (
    id             UUID PRIMARY KEY,
    owner_user_id  UUID         NOT NULL,
    name           VARCHAR(100) NOT NULL,
    account_number VARCHAR(20)  NOT NULL,
    nickname       VARCHAR(50),
    status         VARCHAR(20)  NOT NULL,
    -- new payees only become usable after a cooling period (a common anti-fraud rule)
    active_from    TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_beneficiary UNIQUE (owner_user_id, account_number)
);

CREATE TABLE otp_challenges (
    id         UUID PRIMARY KEY,
    reference  VARCHAR(24)  NOT NULL UNIQUE,
    user_id    UUID         NOT NULL,
    purpose    VARCHAR(30)  NOT NULL,
    -- what the code is bound to (hash of the transfer, or the payee account number)
    binding    VARCHAR(100) NOT NULL,
    code_hash  VARCHAR(64)  NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    attempts   INT          NOT NULL DEFAULT 0,
    consumed   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_otp_user ON otp_challenges (user_id, created_at DESC);

CREATE TABLE fixed_deposits (
    id               UUID PRIMARY KEY,
    reference        VARCHAR(24)    NOT NULL UNIQUE,
    owner_user_id    UUID           NOT NULL,
    account_id       UUID           NOT NULL REFERENCES accounts (id),
    account_number   VARCHAR(20)    NOT NULL,
    principal        NUMERIC(19, 2) NOT NULL CHECK (principal > 0),
    annual_rate      NUMERIC(5, 2)  NOT NULL,
    tenure_months    INT            NOT NULL,
    opened_on        DATE           NOT NULL,
    maturity_date    DATE           NOT NULL,
    maturity_amount  NUMERIC(19, 2) NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    payout_amount    NUMERIC(19, 2),
    closed_at        TIMESTAMPTZ,
    open_journal_id  UUID           NOT NULL,
    close_journal_id UUID,
    created_at       TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_fd_owner ON fixed_deposits (owner_user_id, created_at DESC);
CREATE INDEX idx_fd_due ON fixed_deposits (maturity_date) WHERE status = 'ACTIVE';

CREATE TABLE loans (
    id                    UUID PRIMARY KEY,
    reference             VARCHAR(24)    NOT NULL UNIQUE,
    owner_user_id         UUID           NOT NULL,
    account_id            UUID           NOT NULL REFERENCES accounts (id),
    account_number        VARCHAR(20)    NOT NULL,
    type                  VARCHAR(20)    NOT NULL,
    principal             NUMERIC(19, 2) NOT NULL CHECK (principal > 0),
    annual_rate           NUMERIC(5, 2)  NOT NULL,
    tenure_months         INT            NOT NULL,
    emi                   NUMERIC(19, 2) NOT NULL,
    monthly_income        NUMERIC(19, 2) NOT NULL,
    processing_fee        NUMERIC(19, 2) NOT NULL DEFAULT 0,
    status                VARCHAR(20)    NOT NULL,
    rejection_reason      VARCHAR(200),
    outstanding_principal NUMERIC(19, 2) NOT NULL DEFAULT 0,
    disbursed_at          TIMESTAMPTZ,
    disbursal_journal_id  UUID,
    created_at            TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_loans_owner ON loans (owner_user_id, created_at DESC);

CREATE TABLE loan_installments (
    id             UUID PRIMARY KEY,
    loan_id        UUID           NOT NULL REFERENCES loans (id),
    number         INT            NOT NULL,
    due_date       DATE           NOT NULL,
    emi            NUMERIC(19, 2) NOT NULL,
    principal_part NUMERIC(19, 2) NOT NULL,
    interest_part  NUMERIC(19, 2) NOT NULL,
    late_fee       NUMERIC(19, 2) NOT NULL DEFAULT 0,
    status         VARCHAR(20)    NOT NULL,
    paid_at        TIMESTAMPTZ,
    journal_id     UUID,
    CONSTRAINT uq_installment UNIQUE (loan_id, number)
);
CREATE INDEX idx_installments_due ON loan_installments (due_date) WHERE status IN ('PENDING', 'OVERDUE');

CREATE TABLE cards (
    id                    UUID PRIMARY KEY,
    reference             VARCHAR(24)    NOT NULL UNIQUE,
    owner_user_id         UUID         NOT NULL,
    account_id            UUID         NOT NULL REFERENCES accounts (id),
    account_number        VARCHAR(20)  NOT NULL,
    kind                  VARCHAR(10)  NOT NULL,
    last4                 VARCHAR(4)   NOT NULL,
    pan_hash              VARCHAR(64)  NOT NULL UNIQUE,
    cvv_hash              VARCHAR(64)  NOT NULL,
    holder_name           VARCHAR(100) NOT NULL,
    expiry_month          INT          NOT NULL,
    expiry_year           INT          NOT NULL,
    status                VARCHAR(10)  NOT NULL,
    daily_limit           NUMERIC(19, 2) NOT NULL,
    online_enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    international_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    pin_hash              VARCHAR(100),
    pin_failed_attempts   INT          NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_cards_owner ON cards (owner_user_id, created_at DESC);

CREATE TABLE card_transactions (
    id             UUID PRIMARY KEY,
    reference      VARCHAR(24)    NOT NULL UNIQUE,
    card_id        UUID           NOT NULL REFERENCES cards (id),
    merchant       VARCHAR(100)   NOT NULL,
    amount         NUMERIC(19, 2) NOT NULL,
    channel        VARCHAR(10)    NOT NULL,
    status         VARCHAR(10)    NOT NULL,
    decline_reason VARCHAR(60),
    journal_reference VARCHAR(24),
    created_at     TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_card_tx ON card_transactions (card_id, created_at DESC);

-- Customer-facing reference numbers. Internal UUIDs are never exposed by the public API.
-- The volatile default fills existing journals without firing the append-only trigger (no UPDATE is issued).
ALTER TABLE journals ADD COLUMN reference VARCHAR(24) NOT NULL
    DEFAULT ('TXN' || upper(substr(md5(random()::text || clock_timestamp()::text), 1, 14)));
ALTER TABLE journals ALTER COLUMN reference DROP DEFAULT;
ALTER TABLE journals ADD CONSTRAINT uq_journal_reference UNIQUE (reference);

ALTER TABLE transfers ADD COLUMN reference             VARCHAR(24);
ALTER TABLE transfers ADD COLUMN source_account_number VARCHAR(20);
ALTER TABLE transfers ADD COLUMN target_account_number VARCHAR(20);
UPDATE transfers t SET reference = j.reference FROM journals j WHERE j.id = t.journal_id;
UPDATE transfers t SET source_account_number = a.account_number FROM accounts a WHERE a.id = t.source_account_id;
UPDATE transfers t SET target_account_number = a.account_number FROM accounts a WHERE a.id = t.target_account_id;
ALTER TABLE transfers ALTER COLUMN reference SET NOT NULL;
ALTER TABLE transfers ALTER COLUMN source_account_number SET NOT NULL;
ALTER TABLE transfers ALTER COLUMN target_account_number SET NOT NULL;
ALTER TABLE transfers ADD CONSTRAINT uq_transfer_reference UNIQUE (reference);
CREATE INDEX idx_transfers_target_time ON transfers (target_account_id, created_at DESC);
