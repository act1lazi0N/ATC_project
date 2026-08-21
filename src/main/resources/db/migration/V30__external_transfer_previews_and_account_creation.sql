ALTER TABLE accounts
    ADD COLUMN description VARCHAR(255);

ALTER TABLE audit_logs
    ALTER COLUMN transaction_id DROP NOT NULL;

ALTER TABLE audit_logs
    ADD COLUMN account_id UUID;

ALTER TABLE audit_logs
    DROP CONSTRAINT IF EXISTS chk_audit_logs_event_type;

ALTER TABLE audit_logs
    ADD CONSTRAINT chk_audit_logs_event_type CHECK (
        event_type IN (
            'TRANSFER_INITIATED', 'TRANSFER_COMPLETED', 'TRANSFER_FAILED',
            'TRANSFER_REVERSED', 'TRANSFER_REFUNDED', 'ACCOUNT_FROZEN',
            'ACCOUNT_CLOSED', 'ACCOUNT_CREATED'
        )
    );

ALTER TABLE audit_logs
    ADD CONSTRAINT fk_audit_logs_account
        FOREIGN KEY (account_id) REFERENCES accounts (id);

ALTER TABLE audit_logs
    ADD CONSTRAINT chk_audit_logs_single_target
        CHECK ((transaction_id IS NOT NULL AND account_id IS NULL)
            OR (transaction_id IS NULL AND account_id IS NOT NULL));

CREATE TABLE account_creation_requests
(
    id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id           UUID         NOT NULL,
    idempotency_key   VARCHAR(64)  NOT NULL,
    request_hash      VARCHAR(128) NOT NULL,
    account_id        UUID         NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_account_creation_requests PRIMARY KEY (id),
    CONSTRAINT uk_account_creation_requests_scope UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_account_creation_requests_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_account_creation_requests_account FOREIGN KEY (account_id) REFERENCES accounts (id)
);

CREATE TABLE transfer_previews
(
    id                    UUID         NOT NULL DEFAULT gen_random_uuid(),
    initiator_id          UUID         NOT NULL,
    source_account_id     UUID         NOT NULL,
    destination_account_id UUID        NOT NULL,
    mode                  VARCHAR(20)  NOT NULL,
    amount                NUMERIC(18,2) NOT NULL,
    fee                   NUMERIC(18,2) NOT NULL DEFAULT 0,
    currency              VARCHAR(3)   NOT NULL,
    description           VARCHAR(255),
    request_fingerprint   VARCHAR(128) NOT NULL,
    expires_at            TIMESTAMPTZ  NOT NULL,
    consumed_at           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_transfer_previews PRIMARY KEY (id),
    CONSTRAINT fk_transfer_previews_initiator FOREIGN KEY (initiator_id) REFERENCES users (id),
    CONSTRAINT fk_transfer_previews_source FOREIGN KEY (source_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_transfer_previews_destination FOREIGN KEY (destination_account_id) REFERENCES accounts (id),
    CONSTRAINT chk_transfer_previews_mode CHECK (mode IN ('EXTERNAL', 'OWN_ACCOUNTS')),
    CONSTRAINT chk_transfer_previews_amount CHECK (amount > 0),
    CONSTRAINT chk_transfer_previews_fee CHECK (fee >= 0)
);

CREATE INDEX idx_transfer_previews_expiry ON transfer_previews (expires_at);
CREATE INDEX idx_transfer_previews_initiator ON transfer_previews (initiator_id, created_at);
