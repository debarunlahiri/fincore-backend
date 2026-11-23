-- user_id / account ids reference other services' data by value only (database per service).
CREATE TABLE standing_instructions (
    id                    UUID PRIMARY KEY,
    user_id               UUID           NOT NULL,
    source_account_id     UUID           NOT NULL,
    target_account_number VARCHAR(20)    NOT NULL,
    amount                NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency              VARCHAR(3)     NOT NULL,
    remarks               VARCHAR(140),
    frequency             VARCHAR(10)    NOT NULL,
    next_run_date         DATE           NOT NULL,
    status                VARCHAR(10)    NOT NULL,
    last_run_status       VARCHAR(100),
    last_run_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ    NOT NULL
);
-- the daily job looks for ACTIVE instructions that are due
CREATE INDEX idx_standing_due ON standing_instructions (next_run_date) WHERE status = 'ACTIVE';
CREATE INDEX idx_standing_user ON standing_instructions (user_id);
