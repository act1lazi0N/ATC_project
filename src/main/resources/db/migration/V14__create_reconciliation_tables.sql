CREATE TABLE reconciliation_runs
(
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    currency        CHAR(3)     NOT NULL,
    window_start    TIMESTAMPTZ NOT NULL,
    window_end      TIMESTAMPTZ NOT NULL,
    status          VARCHAR(20) NOT NULL,
    source_count    INTEGER     NOT NULL DEFAULT 0,
    reporting_count INTEGER     NOT NULL DEFAULT 0,
    exception_count INTEGER     NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,

    CONSTRAINT pk_reconciliation_runs PRIMARY KEY (id),
    CONSTRAINT chk_reconciliation_runs_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_reconciliation_runs_window
        CHECK (window_start < window_end)
);

CREATE TABLE reconciliation_exceptions
(
    id               UUID           NOT NULL DEFAULT gen_random_uuid(),
    run_id           UUID           NOT NULL,
    exception_type   VARCHAR(40)    NOT NULL,
    transaction_id   UUID,
    source_amount    NUMERIC(18, 2),
    reporting_amount NUMERIC(18, 2),
    source_status    VARCHAR(20),
    reporting_status VARCHAR(20),
    details          VARCHAR(1000)  NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_reconciliation_exceptions PRIMARY KEY (id),
    CONSTRAINT fk_reconciliation_exceptions_run
        FOREIGN KEY (run_id) REFERENCES reconciliation_runs (id),
    CONSTRAINT chk_reconciliation_exceptions_type
        CHECK (exception_type IN (
            'MISSING_IN_REPORTING',
            'DUPLICATE_IN_REPORTING',
            'UNEXPECTED_IN_REPORTING',
            'AMOUNT_MISMATCH',
            'STATUS_MISMATCH'
        ))
);

CREATE INDEX idx_reconciliation_runs_window
    ON reconciliation_runs (currency, window_start, window_end);

CREATE INDEX idx_reconciliation_exceptions_run_id
    ON reconciliation_exceptions (run_id);

CREATE INDEX idx_reconciliation_exceptions_transaction_id
    ON reconciliation_exceptions (transaction_id);
