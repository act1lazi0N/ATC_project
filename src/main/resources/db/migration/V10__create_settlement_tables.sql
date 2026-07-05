CREATE TABLE settlement_batches
(
    id            UUID           NOT NULL DEFAULT gen_random_uuid(),
    currency      CHAR(3)        NOT NULL,
    gross_amount  NUMERIC(18, 2) NOT NULL,
    fee_amount    NUMERIC(18, 2) NOT NULL,
    net_amount    NUMERIC(18, 2) NOT NULL,
    fee_rate_bps  INTEGER        NOT NULL,
    status        VARCHAR(20)    NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_settlement_batches PRIMARY KEY (id),
    CONSTRAINT chk_settlement_batches_amounts CHECK (
        gross_amount >= 0
        AND fee_amount >= 0
        AND net_amount >= 0
        AND gross_amount = fee_amount + net_amount
    ),
    CONSTRAINT chk_settlement_batches_fee_rate CHECK (fee_rate_bps >= 0 AND fee_rate_bps <= 10000),
    CONSTRAINT chk_settlement_batches_status CHECK (status IN ('OPEN', 'COMPLETED'))
);

CREATE TABLE settlement_items
(
    id                  UUID           NOT NULL DEFAULT gen_random_uuid(),
    settlement_batch_id UUID           NOT NULL,
    transaction_id      UUID           NOT NULL,
    receiver_account_id UUID           NOT NULL,
    gross_amount        NUMERIC(18, 2) NOT NULL,
    fee_amount          NUMERIC(18, 2) NOT NULL,
    net_amount          NUMERIC(18, 2) NOT NULL,
    platform_revenue    NUMERIC(18, 2) NOT NULL,
    receiver_payable    NUMERIC(18, 2) NOT NULL,
    currency            CHAR(3)        NOT NULL,
    payout_status       VARCHAR(20)    NOT NULL,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_settlement_items PRIMARY KEY (id),
    CONSTRAINT fk_settlement_items_batch FOREIGN KEY (settlement_batch_id) REFERENCES settlement_batches (id),
    CONSTRAINT fk_settlement_items_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_settlement_items_receiver_account FOREIGN KEY (receiver_account_id) REFERENCES accounts (id),
    CONSTRAINT uk_settlement_items_transaction UNIQUE (transaction_id),
    CONSTRAINT chk_settlement_items_amounts CHECK (
        gross_amount > 0
        AND fee_amount >= 0
        AND net_amount >= 0
        AND platform_revenue = fee_amount
        AND receiver_payable = net_amount
        AND gross_amount = fee_amount + net_amount
    ),
    CONSTRAINT chk_settlement_items_payout_status CHECK (payout_status IN ('PENDING', 'PAID', 'FAILED'))
);

CREATE INDEX idx_settlement_items_batch_id ON settlement_items (settlement_batch_id);
CREATE INDEX idx_settlement_items_receiver_account_id ON settlement_items (receiver_account_id);
