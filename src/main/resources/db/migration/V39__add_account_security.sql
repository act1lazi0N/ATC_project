ALTER TABLE users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users ADD CONSTRAINT chk_users_auth_version CHECK (auth_version >= 0);

ALTER TABLE refresh_sessions DROP CONSTRAINT chk_refresh_sessions_revoked_reason;
ALTER TABLE refresh_sessions ADD CONSTRAINT chk_refresh_sessions_revoked_reason CHECK (
    revoked_reason IS NULL OR revoked_reason IN (
        'ROTATED', 'LOGOUT', 'EXPIRED', 'SECURITY_REUSE', 'ADMIN_REVOKED',
        'PASSWORD_CHANGED', 'PASSWORD_RESET', 'LOGOUT_ALL'
    )
);

CREATE TABLE password_reset_challenges (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    email VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_password_reset_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_password_reset_terminal CHECK (consumed_at IS NULL OR invalidated_at IS NULL)
);
CREATE UNIQUE INDEX uk_password_reset_active_user ON password_reset_challenges(user_id)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;
CREATE INDEX idx_password_reset_expiry ON password_reset_challenges(expires_at);

ALTER TABLE email_deliveries ADD COLUMN password_reset_challenge_id UUID
    REFERENCES password_reset_challenges(id);
ALTER TABLE email_deliveries ADD COLUMN security_audit_event_id UUID REFERENCES identity_audit_logs(id);
ALTER TABLE email_deliveries ADD CONSTRAINT uk_email_deliveries_password_reset UNIQUE(password_reset_challenge_id);
ALTER TABLE email_deliveries ADD CONSTRAINT uk_email_deliveries_security_event UNIQUE(security_audit_event_id);
ALTER TABLE email_deliveries DROP CONSTRAINT chk_email_deliveries_target;
ALTER TABLE email_deliveries DROP CONSTRAINT chk_email_deliveries_purpose;
ALTER TABLE email_deliveries ADD CONSTRAINT chk_email_deliveries_purpose CHECK (
    purpose IN ('TRANSACTION_NOTIFICATION', 'WEBHOOK_ALERT', 'EMAIL_VERIFICATION', 'PASSWORD_RESET', 'PASSWORD_CHANGED')
);
ALTER TABLE email_deliveries ADD CONSTRAINT chk_email_deliveries_target CHECK (
    num_nonnulls(notification_id, verification_challenge_id, password_reset_challenge_id, security_audit_event_id) = 1
    AND (
        (purpose IN ('TRANSACTION_NOTIFICATION', 'WEBHOOK_ALERT') AND notification_id IS NOT NULL)
        OR (purpose = 'EMAIL_VERIFICATION' AND verification_challenge_id IS NOT NULL)
        OR (purpose = 'PASSWORD_RESET' AND password_reset_challenge_id IS NOT NULL)
        OR (purpose = 'PASSWORD_CHANGED' AND security_audit_event_id IS NOT NULL)
    )
);
