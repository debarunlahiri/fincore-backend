-- Merchant accounts: a customer can also accept payments from other customers.
-- The merchant gets a dedicated MERCHANT-type account. A BUSINESS merchant can build a franchise
-- network: outlets are merchants with a parent and an agreed royalty. Customer payments are held on
-- the system account and paid out in settlements; plan limits are enforced in the application.

CREATE TABLE merchants (
    id                 UUID PRIMARY KEY,
    reference          VARCHAR(24)   NOT NULL UNIQUE,
    owner_user_id      UUID          NOT NULL UNIQUE,
    account_id         UUID          NOT NULL REFERENCES accounts (id),
    account_number     VARCHAR(20)   NOT NULL,
    business_name      VARCHAR(100)  NOT NULL,
    category           VARCHAR(20)   NOT NULL,
    plan               VARCHAR(20)   NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    parent_merchant_id UUID REFERENCES merchants (id),
    royalty_percent    NUMERIC(5, 2) NOT NULL DEFAULT 0 CHECK (royalty_percent >= 0 AND royalty_percent <= 30),
    -- fallback state: royalty held back, and settlement retry back-off after a failure
    royalty_owed        NUMERIC(19, 2) NOT NULL DEFAULT 0,
    settlement_failures INT            NOT NULL DEFAULT 0,
    settlement_retry_at TIMESTAMPTZ,
    settlement_error    VARCHAR(200),
    plan_changed_at    TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_merchants_parent ON merchants (parent_merchant_id);

CREATE TABLE merchant_invites (
    id                 UUID PRIMARY KEY,
    reference          VARCHAR(24)   NOT NULL UNIQUE,
    parent_merchant_id UUID          NOT NULL REFERENCES merchants (id),
    royalty_percent    NUMERIC(5, 2) NOT NULL CHECK (royalty_percent >= 0 AND royalty_percent <= 30),
    note               VARCHAR(140),
    status             VARCHAR(10)   NOT NULL,
    expires_at         TIMESTAMPTZ   NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_merchant_invites_parent ON merchant_invites (parent_merchant_id, created_at DESC);

CREATE TABLE merchant_settlements (
    id                  UUID PRIMARY KEY,
    reference           VARCHAR(24)    NOT NULL UNIQUE,
    merchant_id         UUID           NOT NULL REFERENCES merchants (id),
    royalty_merchant_id UUID REFERENCES merchants (id),
    payment_count       INT            NOT NULL CHECK (payment_count > 0),
    gross_amount        NUMERIC(19, 2) NOT NULL,
    fee_amount          NUMERIC(19, 2) NOT NULL,
    royalty_amount      NUMERIC(19, 2) NOT NULL,
    royalty_held        NUMERIC(19, 2) NOT NULL DEFAULT 0,
    net_amount          NUMERIC(19, 2) NOT NULL,
    triggered_by        VARCHAR(10)    NOT NULL,
    journal_reference   VARCHAR(24)    NOT NULL,
    created_at          TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_merchant_settlements_merchant ON merchant_settlements (merchant_id, created_at DESC);
CREATE INDEX idx_merchant_settlements_royalty ON merchant_settlements (royalty_merchant_id);

CREATE TABLE merchant_payments (
    id                       UUID PRIMARY KEY,
    reference                VARCHAR(24)    NOT NULL UNIQUE,
    merchant_id              UUID           NOT NULL REFERENCES merchants (id),
    payer_user_id            UUID           NOT NULL,
    payer_account_id         UUID           NOT NULL REFERENCES accounts (id),
    payer_account_masked     VARCHAR(20)    NOT NULL,
    amount                   NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    fee                      NUMERIC(19, 2) NOT NULL CHECK (fee >= 0),
    royalty                  NUMERIC(19, 2) NOT NULL CHECK (royalty >= 0),
    net_amount               NUMERIC(19, 2) NOT NULL CHECK (net_amount > 0),
    status                   VARCHAR(10)    NOT NULL,
    settlement_id            UUID REFERENCES merchant_settlements (id),
    note                     VARCHAR(140),
    journal_reference        VARCHAR(24)    NOT NULL,
    refund_journal_reference VARCHAR(24),
    refunded_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_merchant_payments_merchant ON merchant_payments (merchant_id, created_at DESC);
CREATE INDEX idx_merchant_payments_payer_account ON merchant_payments (payer_account_id, created_at DESC);
CREATE INDEX idx_merchant_payments_unsettled ON merchant_payments (merchant_id, created_at)
    WHERE settlement_id IS NULL AND status = 'SUCCESS';

-- Team: sub-roles inside a merchant. The owner (merchants.owner_user_id) is implicit; everyone else is a member.
CREATE TABLE merchant_members (
    id          UUID PRIMARY KEY,
    reference   VARCHAR(24)  NOT NULL UNIQUE,
    merchant_id UUID         NOT NULL REFERENCES merchants (id),
    user_id     UUID         NOT NULL UNIQUE,
    role        VARCHAR(20)  NOT NULL,
    label       VARCHAR(60)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_merchant_members_merchant ON merchant_members (merchant_id);

CREATE TABLE merchant_team_invites (
    id          UUID PRIMARY KEY,
    reference   VARCHAR(24)  NOT NULL UNIQUE,
    merchant_id UUID         NOT NULL REFERENCES merchants (id),
    role        VARCHAR(20)  NOT NULL,
    label       VARCHAR(60)  NOT NULL,
    status      VARCHAR(10)  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_merchant_team_invites_merchant ON merchant_team_invites (merchant_id, created_at DESC);
