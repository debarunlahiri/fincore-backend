-- Guest (not signed in) merchant payments, paid through Razorpay.
-- GATEWAY is the counter-party of money that arrives from outside the bank; like SYSTEM it may go negative.
ALTER TABLE accounts DROP CONSTRAINT chk_balance_non_negative;
ALTER TABLE accounts DROP CONSTRAINT chk_customer_account_has_owner;
ALTER TABLE accounts ADD CONSTRAINT chk_balance_non_negative CHECK (type IN ('SYSTEM', 'GATEWAY') OR balance >= 0);
ALTER TABLE accounts ADD CONSTRAINT chk_customer_account_has_owner CHECK (type IN ('SYSTEM', 'GATEWAY') OR owner_user_id IS NOT NULL);
INSERT INTO accounts (id, account_number, owner_user_id, type, currency, balance, status, daily_transfer_limit, version, created_at)
VALUES ('00000000-0000-0000-0000-000000000002', 'FCGATEWAY0001', NULL, 'GATEWAY', 'INR', 0, 'ACTIVE', 0, 0, now());

-- A guest payment has no customer or account behind it.
ALTER TABLE merchant_payments ALTER COLUMN payer_user_id DROP NOT NULL;
ALTER TABLE merchant_payments ALTER COLUMN payer_account_id DROP NOT NULL;

CREATE TABLE guest_payments (
    id                  UUID PRIMARY KEY,
    reference           VARCHAR(24)    NOT NULL UNIQUE,
    merchant_id         UUID           NOT NULL REFERENCES merchants (id),
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    note                VARCHAR(140),
    payer_name          VARCHAR(80)    NOT NULL,
    payer_email         VARCHAR(254)   NOT NULL,
    status              VARCHAR(10)    NOT NULL,
    razorpay_order_id   VARCHAR(40)    NOT NULL UNIQUE,
    razorpay_payment_id VARCHAR(40),
    merchant_payment_id UUID REFERENCES merchant_payments (id),
    created_at          TIMESTAMPTZ    NOT NULL,
    paid_at             TIMESTAMPTZ
);
CREATE INDEX idx_guest_payments_merchant ON guest_payments (merchant_id, created_at DESC);
