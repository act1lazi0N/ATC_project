CREATE TABLE refresh_sessions
(
    id                     UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id                UUID         NOT NULL,
    refresh_token_hash     VARCHAR(64)  NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at             TIMESTAMPTZ  NOT NULL,
    last_used_at           TIMESTAMPTZ,
    revoked_at             TIMESTAMPTZ,
    replaced_by_session_id UUID,

    CONSTRAINT pk_refresh_sessions PRIMARY KEY (id),
    CONSTRAINT uk_refresh_sessions_token_hash UNIQUE (refresh_token_hash),
    CONSTRAINT fk_refresh_sessions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_refresh_sessions_replaced_by FOREIGN KEY (replaced_by_session_id)
        REFERENCES refresh_sessions (id)
);

CREATE INDEX idx_refresh_sessions_user_id ON refresh_sessions (user_id);
CREATE INDEX idx_refresh_sessions_expires_at ON refresh_sessions (expires_at);
