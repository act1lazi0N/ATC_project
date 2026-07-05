ALTER TYPE audit_event_type ADD VALUE IF NOT EXISTS 'TRANSFER_REFUNDED';

ALTER TABLE transactions
    ADD COLUMN original_transaction_id UUID,
    ADD COLUMN refunded_amount NUMERIC(18, 2) NOT NULL DEFAULT 0;

ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_original_transaction
        FOREIGN KEY (original_transaction_id) REFERENCES transactions (id),
    ADD CONSTRAINT chk_transactions_refunded_amount
        CHECK (refunded_amount >= 0);

CREATE INDEX idx_transactions_original_transaction_id
    ON transactions (original_transaction_id);
