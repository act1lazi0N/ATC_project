CREATE TABLE smart_otp_profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    ever_enrolled BOOLEAN NOT NULL DEFAULT FALSE,
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    window_started_at TIMESTAMPTZ,
    blocked_until TIMESTAMPTZ,
    grant_hash VARCHAR(64),
    grant_expires_at TIMESTAMPTZ,
    grant_auth_version BIGINT,
    CHECK (num_nonnulls(grant_hash, grant_expires_at, grant_auth_version) IN (0, 3))
);
CREATE TABLE smart_otp_credentials (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'REVOKED')),
    secret_ciphertext TEXT,
    key_id VARCHAR(64) NOT NULL,
    auth_version BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((state = 'REVOKED' AND secret_ciphertext IS NULL) OR
           (state IN ('PENDING', 'ACTIVE') AND secret_ciphertext IS NOT NULL))
);
CREATE TABLE smart_otp_failures (
    user_id UUID NOT NULL REFERENCES users(id),
    failed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_smart_otp_failures ON smart_otp_failures(user_id, failed_at);
CREATE UNIQUE INDEX uk_smart_otp_active ON smart_otp_credentials(user_id) WHERE state = 'ACTIVE';
CREATE UNIQUE INDEX uk_smart_otp_pending ON smart_otp_credentials(user_id) WHERE state = 'PENDING';
CREATE TABLE smart_otp_challenges (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    credential_id UUID NOT NULL REFERENCES smart_otp_credentials(id),
    purpose VARCHAR(32) NOT NULL CHECK (purpose IN
        ('ENROLLMENT', 'TRANSFER', 'REPLACE_DEVICE', 'REVOKE_DEVICE', 'REGENERATE_RECOVERY_CODES')),
    auth_version BIGINT NOT NULL,
    preview_id UUID REFERENCES transfer_previews(id),
    idempotency_key VARCHAR(64),
    binding_hash VARCHAR(64),
    payload_base64 TEXT NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'VERIFIED', 'CONSUMED', 'REVOKED')),
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > created_at),
    CHECK ((purpose = 'TRANSFER' AND num_nonnulls(preview_id, idempotency_key, binding_hash) = 3)
        OR (purpose <> 'TRANSFER' AND num_nonnulls(preview_id, idempotency_key, binding_hash) = 0)),
    UNIQUE (user_id, preview_id)
);
CREATE INDEX idx_smart_otp_challenge_credential ON smart_otp_challenges(credential_id);
CREATE TABLE smart_otp_recovery_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    code_hash VARCHAR(64) NOT NULL,
    consumed_at TIMESTAMPTZ,
    UNIQUE (user_id, code_hash)
);
ALTER TABLE transfer_previews ADD COLUMN smart_otp_bound BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE refresh_sessions DROP CONSTRAINT chk_refresh_sessions_revoked_reason;
ALTER TABLE refresh_sessions ADD CONSTRAINT chk_refresh_sessions_revoked_reason CHECK (
    revoked_reason IS NULL OR revoked_reason IN ('ROTATED', 'LOGOUT', 'EXPIRED', 'SECURITY_REUSE',
    'ADMIN_REVOKED', 'PASSWORD_CHANGED', 'PASSWORD_RESET', 'LOGOUT_ALL', 'SMART_OTP_RECOVERY')
);
ALTER TABLE email_deliveries DROP CONSTRAINT chk_email_deliveries_purpose;
ALTER TABLE email_deliveries ADD CONSTRAINT chk_email_deliveries_purpose CHECK (
    purpose IN ('TRANSACTION_NOTIFICATION', 'WEBHOOK_ALERT', 'EMAIL_VERIFICATION',
        'PASSWORD_RESET', 'PASSWORD_CHANGED', 'SMART_OTP_SECURITY')
);
ALTER TABLE email_deliveries DROP CONSTRAINT chk_email_deliveries_target;
ALTER TABLE email_deliveries ADD CONSTRAINT chk_email_deliveries_target CHECK (
    num_nonnulls(notification_id, verification_challenge_id, password_reset_challenge_id, security_audit_event_id) = 1
    AND ((purpose IN ('TRANSACTION_NOTIFICATION', 'WEBHOOK_ALERT') AND notification_id IS NOT NULL)
        OR (purpose = 'EMAIL_VERIFICATION' AND verification_challenge_id IS NOT NULL)
        OR (purpose = 'PASSWORD_RESET' AND password_reset_challenge_id IS NOT NULL)
        OR (purpose IN ('PASSWORD_CHANGED', 'SMART_OTP_SECURITY') AND security_audit_event_id IS NOT NULL))
);
