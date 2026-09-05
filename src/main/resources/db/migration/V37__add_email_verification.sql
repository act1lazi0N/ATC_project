ALTER TABLE users
    ADD COLUMN email_verified_at TIMESTAMPTZ;

CREATE TABLE email_verification_challenges
(
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    consumed_at    TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_email_verification_challenges PRIMARY KEY (id),
    CONSTRAINT fk_email_verification_challenges_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_email_verification_challenge_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_email_verification_challenge_terminal CHECK (
        consumed_at IS NULL OR invalidated_at IS NULL
    )
);

CREATE UNIQUE INDEX uk_email_verification_challenges_active_user
    ON email_verification_challenges (user_id)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;

CREATE INDEX idx_email_verification_challenges_expiry
    ON email_verification_challenges (expires_at)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;
