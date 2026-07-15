ALTER TABLE idempotency_records
    ADD COLUMN operation VARCHAR(30),
    ADD COLUMN initiator_email VARCHAR(255);

UPDATE idempotency_records ir
SET operation = CASE
        WHEN t.original_transaction_id IS NULL THEN 'TRANSFER'
        WHEN EXISTS (
            SELECT 1
            FROM audit_logs al
            WHERE al.transaction_id = t.original_transaction_id
              AND al.event_type = 'TRANSFER_REFUNDED'
        ) THEN 'REFUND'
        ELSE 'REVERSAL'
    END,
    initiator_email = COALESCE(u.email, 'unknown@local')
FROM transactions t
LEFT JOIN users u ON u.id = t.initiated_by
WHERE ir.transaction_id = t.id;

UPDATE idempotency_records
SET operation = 'TRANSFER',
    initiator_email = 'unknown@local'
WHERE operation IS NULL
   OR initiator_email IS NULL;

ALTER TABLE idempotency_records
    ALTER COLUMN operation SET NOT NULL,
    ALTER COLUMN initiator_email SET NOT NULL;

ALTER TABLE idempotency_records
    DROP CONSTRAINT uk_idempotency_records_key;

ALTER TABLE idempotency_records
    ADD CONSTRAINT uk_idempotency_records_scope
        UNIQUE (idempotency_key, operation, initiator_email);

ALTER TABLE transactions
    DROP CONSTRAINT uk_transactions_idempotency;

ALTER TABLE transactions
    ADD CONSTRAINT uk_transactions_idempotency_key
        UNIQUE (idempotency_key, initiated_by);
