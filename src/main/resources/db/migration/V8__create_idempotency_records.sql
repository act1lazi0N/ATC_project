CREATE TABLE idempotency_records
(
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    idempotency_key  VARCHAR(64)  NOT NULL,
    request_hash     VARCHAR(128) NOT NULL,
    transaction_id   UUID,
    response_payload JSONB,
    status           VARCHAR(30)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ,

    CONSTRAINT pk_idempotency_records PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_records_key UNIQUE (idempotency_key),
    CONSTRAINT fk_idempotency_records_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT chk_idempotency_records_status CHECK (
        status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'EXPIRED')
    )
);

CREATE INDEX idx_idempotency_records_status_created_at
    ON idempotency_records (status, created_at);
