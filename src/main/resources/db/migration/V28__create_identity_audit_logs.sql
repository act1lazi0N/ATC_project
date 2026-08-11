CREATE TABLE identity_audit_logs
(
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id       UUID,
    event_type    VARCHAR(40)  NOT NULL,
    identity_hash VARCHAR(64),
    ip_address    VARCHAR(45),
    metadata      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_identity_audit_logs PRIMARY KEY (id)
);

CREATE INDEX idx_identity_audit_logs_user_created
    ON identity_audit_logs (user_id, created_at);
CREATE INDEX idx_identity_audit_logs_identity_created
    ON identity_audit_logs (identity_hash, created_at);
