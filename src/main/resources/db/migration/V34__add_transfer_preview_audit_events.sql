-- Add audit event types for transfer preview lifecycle
ALTER TABLE audit_logs
    DROP CONSTRAINT IF EXISTS chk_audit_logs_event_type;

ALTER TABLE audit_logs
    ADD CONSTRAINT chk_audit_logs_event_type CHECK (
        event_type IN (
            'TRANSFER_INITIATED', 'TRANSFER_COMPLETED', 'TRANSFER_FAILED',
            'TRANSFER_REVERSED', 'TRANSFER_REFUNDED', 'ACCOUNT_FROZEN',
            'ACCOUNT_CLOSED', 'ACCOUNT_CREATED',
            'TRANSFER_PREVIEW_CREATED', 'TRANSFER_PREVIEW_EXPIRED'
        )
    );
