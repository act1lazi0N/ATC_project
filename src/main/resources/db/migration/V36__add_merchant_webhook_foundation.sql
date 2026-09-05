CREATE TABLE webhook_endpoints
(
    id                        UUID          NOT NULL DEFAULT gen_random_uuid(),
    owner_id                  UUID          NOT NULL,
    name                      VARCHAR(100)  NOT NULL,
    canonical_url             VARCHAR(2048) NOT NULL,
    signing_secret_ciphertext VARCHAR(1024) NOT NULL,
    signing_secret_nonce      VARCHAR(128)  NOT NULL,
    secret_key_version        VARCHAR(50)   NOT NULL,
    secret_hint               VARCHAR(32)   NOT NULL,
    state                     VARCHAR(20)   NOT NULL DEFAULT 'ENABLED',
    version                   BIGINT        NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_webhook_endpoints PRIMARY KEY (id),
    CONSTRAINT fk_webhook_endpoints_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT uk_webhook_endpoints_owner_url UNIQUE (owner_id, canonical_url),
    CONSTRAINT chk_webhook_endpoints_state CHECK (state IN ('ENABLED', 'DISABLED'))
);

CREATE TABLE webhook_endpoint_subscriptions
(
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    endpoint_id   UUID        NOT NULL,
    event_type    VARCHAR(50) NOT NULL,
    subscribed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_webhook_endpoint_subscriptions PRIMARY KEY (id),
    CONSTRAINT fk_webhook_endpoint_subscriptions_endpoint
        FOREIGN KEY (endpoint_id) REFERENCES webhook_endpoints (id) ON DELETE CASCADE,
    CONSTRAINT uk_webhook_endpoint_subscriptions_event UNIQUE (endpoint_id, event_type),
    CONSTRAINT chk_webhook_endpoint_subscriptions_event CHECK (
        event_type IN ('TRANSFER_COMPLETED', 'REVERSAL_COMPLETED', 'REFUND_COMPLETED')
    )
);

CREATE TABLE webhook_deliveries
(
    id                 UUID         NOT NULL,
    endpoint_id        UUID         NOT NULL,
    outbox_event_id    UUID         NOT NULL,
    event_type         VARCHAR(100) NOT NULL,
    payload            JSONB        NOT NULL,
    status             VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    attempt_count      INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMPTZ,
    claim_token        UUID,
    last_http_status   INTEGER,
    last_error         VARCHAR(500),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    delivered_at       TIMESTAMPTZ,

    CONSTRAINT pk_webhook_deliveries PRIMARY KEY (id),
    CONSTRAINT fk_webhook_deliveries_endpoint FOREIGN KEY (endpoint_id) REFERENCES webhook_endpoints (id),
    CONSTRAINT fk_webhook_deliveries_outbox_event FOREIGN KEY (outbox_event_id) REFERENCES outbox_events (id),
    CONSTRAINT uk_webhook_deliveries_event_endpoint UNIQUE (outbox_event_id, endpoint_id),
    CONSTRAINT chk_webhook_deliveries_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED', 'DEAD_LETTERED', 'CANCELLED')
    ),
    CONSTRAINT chk_webhook_deliveries_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_webhook_deliveries_http_status CHECK (
        last_http_status IS NULL OR last_http_status BETWEEN 100 AND 599
    )
);

CREATE TABLE webhook_delivery_attempts
(
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    delivery_id   UUID        NOT NULL,
    attempt_number INTEGER     NOT NULL,
    outcome       VARCHAR(30) NOT NULL,
    http_status   INTEGER,
    duration_ms   BIGINT      NOT NULL,
    error_context VARCHAR(500),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_webhook_delivery_attempts PRIMARY KEY (id),
    CONSTRAINT fk_webhook_delivery_attempts_delivery
        FOREIGN KEY (delivery_id) REFERENCES webhook_deliveries (id) ON DELETE CASCADE,
    CONSTRAINT uk_webhook_delivery_attempts_number UNIQUE (delivery_id, attempt_number),
    CONSTRAINT chk_webhook_delivery_attempts_number CHECK (attempt_number > 0),
    CONSTRAINT chk_webhook_delivery_attempts_outcome CHECK (
        outcome IN ('DELIVERED', 'RETRYABLE_FAILURE', 'TERMINAL_FAILURE', 'CANCELLED')
    ),
    CONSTRAINT chk_webhook_delivery_attempts_http_status CHECK (
        http_status IS NULL OR http_status BETWEEN 100 AND 599
    ),
    CONSTRAINT chk_webhook_delivery_attempts_duration CHECK (duration_ms >= 0)
);

CREATE INDEX idx_webhook_endpoints_owner_state
    ON webhook_endpoints (owner_id, state, created_at, id);

CREATE INDEX idx_webhook_subscriptions_event
    ON webhook_endpoint_subscriptions (event_type, subscribed_at, endpoint_id);

CREATE INDEX idx_webhook_deliveries_endpoint_created
    ON webhook_deliveries (endpoint_id, created_at DESC, id DESC);

CREATE INDEX idx_webhook_deliveries_publishable
    ON webhook_deliveries (status, next_attempt_at, created_at, id);

CREATE INDEX idx_webhook_delivery_attempts_delivery
    ON webhook_delivery_attempts (delivery_id, attempt_number);

CREATE TRIGGER trg_webhook_endpoints_updated_at
    BEFORE UPDATE
    ON webhook_endpoints
    FOR EACH ROW
EXECUTE FUNCTION trigger_set_updated_at();

CREATE TRIGGER trg_webhook_deliveries_updated_at
    BEFORE UPDATE
    ON webhook_deliveries
    FOR EACH ROW
EXECUTE FUNCTION trigger_set_updated_at();
