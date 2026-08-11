ALTER TABLE outbox_events
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN last_error VARCHAR(500);

ALTER TABLE outbox_events
    DROP CONSTRAINT chk_outbox_events_status;

ALTER TABLE outbox_events
    ADD CONSTRAINT chk_outbox_events_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED'));

CREATE INDEX idx_outbox_events_retry
    ON outbox_events (status, next_attempt_at, created_at);
