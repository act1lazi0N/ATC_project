CREATE TABLE payment_qr_codes (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    type VARCHAR(20) NOT NULL,
    state VARCHAR(20) NOT NULL,
    amount NUMERIC(18,2),
    currency VARCHAR(3) NOT NULL,
    description VARCHAR(255),
    idempotency_key VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    paid_at TIMESTAMPTZ,
    transaction_id UUID UNIQUE REFERENCES transactions(id),
    CONSTRAINT uk_payment_qr_creation UNIQUE(owner_id, idempotency_key),
    CONSTRAINT chk_payment_qr_currency CHECK (currency = 'VND'),
    CONSTRAINT chk_payment_qr_type CHECK (
        (type = 'ACCOUNT' AND amount IS NULL AND description IS NULL AND expires_at IS NULL AND state <> 'PAID')
        OR (type = 'PAYMENT_REQUEST' AND amount IS NOT NULL AND amount >= 1000 AND expires_at IS NOT NULL AND expires_at > created_at)),
    CONSTRAINT chk_payment_qr_state CHECK (
        (state = 'ACTIVE' AND transaction_id IS NULL AND paid_at IS NULL AND revoked_at IS NULL)
        OR (state = 'PAID' AND transaction_id IS NOT NULL AND paid_at IS NOT NULL AND revoked_at IS NULL)
        OR (state = 'REVOKED' AND transaction_id IS NULL AND paid_at IS NULL AND revoked_at IS NOT NULL))
);
CREATE UNIQUE INDEX uk_payment_qr_active_account ON payment_qr_codes(account_id)
    WHERE type = 'ACCOUNT' AND state = 'ACTIVE';
CREATE INDEX idx_payment_qr_account_created ON payment_qr_codes(account_id, created_at DESC, id DESC);
ALTER TABLE transfer_previews ADD COLUMN qr_code_id UUID REFERENCES payment_qr_codes(id);
CREATE INDEX idx_transfer_preview_qr ON transfer_previews(qr_code_id) WHERE qr_code_id IS NOT NULL;

ALTER TABLE audit_logs DROP CONSTRAINT chk_audit_logs_event_type;
ALTER TABLE audit_logs ADD CONSTRAINT chk_audit_logs_event_type CHECK (event_type IN (
    'TRANSFER_INITIATED', 'TRANSFER_COMPLETED', 'TRANSFER_FAILED', 'TRANSFER_REVERSED',
    'TRANSFER_REFUNDED', 'ACCOUNT_FROZEN', 'ACCOUNT_CLOSED', 'ACCOUNT_CREATED',
    'TRANSFER_PREVIEW_CREATED', 'TRANSFER_PREVIEW_EXPIRED', 'QR_CREATED', 'QR_REVOKED', 'QR_PAID'
));
