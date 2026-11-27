-- Customer-facing reference number and source account number; internal UUIDs are never exposed by the API.
ALTER TABLE standing_instructions ADD COLUMN reference             VARCHAR(24);
ALTER TABLE standing_instructions ADD COLUMN source_account_number VARCHAR(20);
UPDATE standing_instructions SET reference = 'SIN' || upper(substr(md5(id::text), 1, 14)), source_account_number = 'UNKNOWN';
ALTER TABLE standing_instructions ALTER COLUMN reference SET NOT NULL;
ALTER TABLE standing_instructions ALTER COLUMN source_account_number SET NOT NULL;
ALTER TABLE standing_instructions ADD CONSTRAINT uq_instruction_reference UNIQUE (reference);
