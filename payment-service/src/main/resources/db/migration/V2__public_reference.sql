-- Customer-facing reference number and account number; internal UUIDs are never exposed by the API.
ALTER TABLE payments ADD COLUMN reference      VARCHAR(24);
ALTER TABLE payments ADD COLUMN account_number VARCHAR(20);
UPDATE payments SET reference = 'PAY' || upper(substr(md5(id::text), 1, 14)), account_number = 'UNKNOWN';
ALTER TABLE payments ALTER COLUMN reference SET NOT NULL;
ALTER TABLE payments ALTER COLUMN account_number SET NOT NULL;
ALTER TABLE payments ADD CONSTRAINT uq_payment_reference UNIQUE (reference);
