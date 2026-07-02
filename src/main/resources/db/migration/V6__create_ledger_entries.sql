CREATE TABLE ledger_entries
(
    id             UUID           NOT NULL DEFAULT gen_random_uuid(),
    transaction_id UUID           NOT NULL,
    account_id     UUID           NOT NULL,
    direction      VARCHAR(10)    NOT NULL,
    amount         NUMERIC(18, 2) NOT NULL,
    currency       CHAR(3)        NOT NULL,
    entry_type     VARCHAR(50)    NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT fk_ledger_entries_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_ledger_entries_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT chk_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_entries_type CHECK (entry_type IN ('TRANSFER', 'REVERSAL', 'REFUND', 'ADJUSTMENT')),
    CONSTRAINT chk_ledger_entries_amount CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries (transaction_id);
CREATE INDEX idx_ledger_entries_account_id_created_at ON ledger_entries (account_id, created_at);
