ALTER TABLE transactions
    ADD COLUMN operation VARCHAR(30);

UPDATE transactions t
SET operation = CASE
        WHEN t.original_transaction_id IS NULL THEN 'TRANSFER'
        WHEN EXISTS (
            SELECT 1
            FROM audit_logs al
            WHERE al.transaction_id = t.original_transaction_id
              AND al.event_type = 'TRANSFER_REFUNDED'
        ) THEN 'REFUND'
        ELSE 'REVERSAL'
    END;

ALTER TABLE transactions
    ALTER COLUMN operation SET NOT NULL;

ALTER TABLE transactions
    ADD CONSTRAINT chk_transactions_operation
        CHECK (operation IN ('TRANSFER', 'REVERSAL', 'REFUND'));

ALTER TABLE transactions
    DROP CONSTRAINT IF EXISTS uk_transactions_idempotency_key;

ALTER TABLE transactions
    ADD CONSTRAINT uk_transactions_idempotency_scope
        UNIQUE (idempotency_key, operation, initiated_by);
