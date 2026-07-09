ALTER TABLE settlement_batches
    DROP CONSTRAINT chk_settlement_batches_status;

UPDATE settlement_batches
SET status = 'PENDING'
WHERE status = 'OPEN';

UPDATE settlement_batches
SET status = 'PAID'
WHERE status = 'COMPLETED';

ALTER TABLE settlement_batches
    ADD CONSTRAINT chk_settlement_batches_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'PAID', 'FAILED', 'CANCELLED'));

ALTER TABLE settlement_items
    DROP CONSTRAINT chk_settlement_items_payout_status;

ALTER TABLE settlement_items
    ADD CONSTRAINT chk_settlement_items_payout_status
        CHECK (payout_status IN ('PENDING', 'PROCESSING', 'PAID', 'FAILED'));

UPDATE accounts
SET account_type = 'CLEARING'
WHERE account_number LIKE 'CLEARING-%';

UPDATE accounts
SET account_type = 'RECEIVER_PAYABLE'
WHERE account_number LIKE 'PAYABLE-%';

UPDATE accounts
SET account_type = 'PLATFORM_REVENUE'
WHERE account_number LIKE 'REVENUE-%';

CREATE TABLE payout_attempts
(
    id                 UUID         NOT NULL DEFAULT gen_random_uuid(),
    settlement_item_id UUID         NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    failure_reason     VARCHAR(500),
    attempted_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_payout_attempts PRIMARY KEY (id),
    CONSTRAINT fk_payout_attempts_settlement_item
        FOREIGN KEY (settlement_item_id) REFERENCES settlement_items (id),
    CONSTRAINT chk_payout_attempts_status
        CHECK (status IN ('PROCESSING', 'PAID', 'FAILED'))
);

CREATE INDEX idx_payout_attempts_settlement_item_id
    ON payout_attempts (settlement_item_id);
