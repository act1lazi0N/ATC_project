ALTER TABLE settlement_batches
    ADD COLUMN idempotency_key VARCHAR(64),
    ADD COLUMN cutoff_completed_at TIMESTAMPTZ;

UPDATE settlement_batches
SET idempotency_key = id::TEXT,
    cutoff_completed_at = created_at
WHERE idempotency_key IS NULL
   OR cutoff_completed_at IS NULL;

ALTER TABLE settlement_batches
    ALTER COLUMN idempotency_key SET NOT NULL,
    ALTER COLUMN cutoff_completed_at SET NOT NULL,
    ADD CONSTRAINT uk_settlement_batches_idempotency UNIQUE (idempotency_key);

ALTER TABLE ledger_entries
    DROP CONSTRAINT chk_ledger_entries_type;

ALTER TABLE ledger_entries
    ADD CONSTRAINT chk_ledger_entries_type
        CHECK (entry_type IN ('TRANSFER', 'REVERSAL', 'REFUND', 'SETTLEMENT', 'ADJUSTMENT'));

INSERT INTO users (full_name, email, password_hash, role)
VALUES ('Aries System', 'system@aries.internal', 'not-used', 'ADMIN')
ON CONFLICT (email) DO NOTHING;

INSERT INTO accounts (user_id, account_number, account_type, balance, currency, status)
SELECT u.id, account_number, 'BUSINESS', 0, 'VND', 'ACTIVE'
FROM users u
CROSS JOIN (
    VALUES
        ('CLEARING-VND'),
        ('PAYABLE-VND'),
        ('REVENUE-VND')
) AS system_accounts(account_number)
WHERE u.email = 'system@aries.internal'
ON CONFLICT (account_number) DO NOTHING;
