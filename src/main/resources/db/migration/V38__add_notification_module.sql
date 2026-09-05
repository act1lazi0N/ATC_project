CREATE TABLE notification_preferences
(
    user_id                       UUID        NOT NULL,
    transaction_email_enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    webhook_alert_email_enabled  BOOLEAN     NOT NULL DEFAULT TRUE,
    version                       BIGINT      NOT NULL DEFAULT 0,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_notification_preferences PRIMARY KEY (user_id),
    CONSTRAINT fk_notification_preferences_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE notifications
(
    id                UUID         NOT NULL,
    recipient_user_id UUID         NOT NULL,
    source_kind       VARCHAR(30)  NOT NULL,
    source_id         UUID         NOT NULL,
    source_version    BIGINT       NOT NULL DEFAULT 0,
    notification_type VARCHAR(60)  NOT NULL,
    title             VARCHAR(160) NOT NULL,
    message           VARCHAR(500) NOT NULL,
    payload           JSONB        NOT NULL,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_user_id) REFERENCES users (id),
    CONSTRAINT uk_notifications_source_recipient UNIQUE (
        source_kind, source_id, source_version, recipient_user_id, notification_type
    ),
    CONSTRAINT chk_notifications_source_kind CHECK (
        source_kind IN ('OUTBOX_EVENT', 'WEBHOOK_ENDPOINT', 'WEBHOOK_DELIVERY')
    ),
    CONSTRAINT chk_notifications_type CHECK (
        notification_type IN (
            'TRANSFER_COMPLETED',
            'REVERSAL_COMPLETED',
            'REFUND_COMPLETED',
            'WEBHOOK_ENDPOINT_DISABLED',
            'WEBHOOK_DELIVERY_DEAD_LETTERED'
        )
    )
);

CREATE TABLE email_deliveries
(
    id                        UUID         NOT NULL,
    purpose                   VARCHAR(40)  NOT NULL,
    notification_id           UUID,
    verification_challenge_id UUID,
    status                    VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    attempt_count             INTEGER      NOT NULL DEFAULT 0,
    cycle_attempt_count       INTEGER      NOT NULL DEFAULT 0,
    redrive_count             INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at           TIMESTAMPTZ,
    claim_token               UUID,
    provider_message_id       VARCHAR(255),
    last_error_code           VARCHAR(100),
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    delivered_at              TIMESTAMPTZ,

    CONSTRAINT pk_email_deliveries PRIMARY KEY (id),
    CONSTRAINT fk_email_deliveries_notification
        FOREIGN KEY (notification_id) REFERENCES notifications (id) ON DELETE CASCADE,
    CONSTRAINT fk_email_deliveries_verification
        FOREIGN KEY (verification_challenge_id) REFERENCES email_verification_challenges (id) ON DELETE CASCADE,
    CONSTRAINT uk_email_deliveries_notification UNIQUE (notification_id),
    CONSTRAINT uk_email_deliveries_verification UNIQUE (verification_challenge_id),
    CONSTRAINT chk_email_deliveries_target CHECK (
        (notification_id IS NOT NULL AND verification_challenge_id IS NULL)
        OR (notification_id IS NULL AND verification_challenge_id IS NOT NULL)
    ),
    CONSTRAINT chk_email_deliveries_purpose CHECK (
        purpose IN ('TRANSACTION_NOTIFICATION', 'WEBHOOK_ALERT', 'EMAIL_VERIFICATION')
    ),
    CONSTRAINT chk_email_deliveries_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED', 'DEAD_LETTERED', 'CANCELLED')
    ),
    CONSTRAINT chk_email_deliveries_attempts CHECK (
        attempt_count >= 0 AND cycle_attempt_count >= 0 AND redrive_count >= 0
    )
);

CREATE TABLE email_delivery_attempts
(
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    delivery_id    UUID         NOT NULL,
    attempt_number INTEGER      NOT NULL,
    outcome        VARCHAR(30)  NOT NULL,
    provider_code  VARCHAR(100),
    duration_ms    BIGINT       NOT NULL,
    error_context  VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_email_delivery_attempts PRIMARY KEY (id),
    CONSTRAINT fk_email_delivery_attempts_delivery
        FOREIGN KEY (delivery_id) REFERENCES email_deliveries (id) ON DELETE CASCADE,
    CONSTRAINT uk_email_delivery_attempts_number UNIQUE (delivery_id, attempt_number),
    CONSTRAINT chk_email_delivery_attempts_number CHECK (attempt_number > 0),
    CONSTRAINT chk_email_delivery_attempts_outcome CHECK (
        outcome IN ('DELIVERED', 'RETRYABLE_FAILURE', 'TERMINAL_FAILURE', 'CANCELLED')
    ),
    CONSTRAINT chk_email_delivery_attempts_duration CHECK (duration_ms >= 0)
);

CREATE INDEX idx_notifications_recipient_feed
    ON notifications (recipient_user_id, created_at DESC, id DESC);

CREATE INDEX idx_notifications_recipient_unread
    ON notifications (recipient_user_id, created_at DESC, id DESC)
    WHERE read_at IS NULL;

CREATE INDEX idx_email_deliveries_publishable
    ON email_deliveries (status, next_attempt_at, created_at, id);

CREATE INDEX idx_email_delivery_attempts_delivery
    ON email_delivery_attempts (delivery_id, attempt_number);

CREATE TRIGGER trg_notification_preferences_updated_at
    BEFORE UPDATE ON notification_preferences
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

CREATE TRIGGER trg_email_deliveries_updated_at
    BEFORE UPDATE ON email_deliveries
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();
