-- Payees can now be a FinCore account, an external bank account (account number + IFSC) or a UPI id.
-- "identifier" is the normalised unique key per owner; "reference" is the customer-facing id (BEN...).
ALTER TABLE beneficiaries ADD COLUMN reference  VARCHAR(24);
ALTER TABLE beneficiaries ADD COLUMN type       VARCHAR(10) NOT NULL DEFAULT 'FINCORE';
ALTER TABLE beneficiaries ADD COLUMN identifier VARCHAR(80);
ALTER TABLE beneficiaries ADD COLUMN ifsc       VARCHAR(11);
ALTER TABLE beneficiaries ADD COLUMN bank_name  VARCHAR(80);
ALTER TABLE beneficiaries ADD COLUMN upi_id     VARCHAR(100);

UPDATE beneficiaries
SET reference  = 'BEN' || upper(substr(md5(id::text), 1, 14)),
    identifier = account_number,
    bank_name  = 'FinCore Bank';

ALTER TABLE beneficiaries ALTER COLUMN reference  SET NOT NULL;
ALTER TABLE beneficiaries ALTER COLUMN identifier SET NOT NULL;
ALTER TABLE beneficiaries ALTER COLUMN account_number DROP NOT NULL;
ALTER TABLE beneficiaries DROP CONSTRAINT uq_beneficiary;
ALTER TABLE beneficiaries ADD CONSTRAINT uq_beneficiary UNIQUE (owner_user_id, identifier);
ALTER TABLE beneficiaries ADD CONSTRAINT uq_beneficiary_reference UNIQUE (reference);
