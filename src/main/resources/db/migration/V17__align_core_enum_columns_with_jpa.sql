ALTER TABLE users
    ALTER COLUMN role DROP DEFAULT;
ALTER TABLE users
    ALTER COLUMN role TYPE VARCHAR(20) USING role::text;
ALTER TABLE users
    ALTER COLUMN role SET DEFAULT 'USER';
ALTER TABLE users
    ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN'));

ALTER TABLE accounts
    ALTER COLUMN account_type DROP DEFAULT;
ALTER TABLE accounts
    ALTER COLUMN account_type TYPE VARCHAR(20) USING account_type::text;
ALTER TABLE accounts
    ALTER COLUMN account_type SET DEFAULT 'PERSONAL';
ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_type CHECK (
        account_type IN ('PERSONAL', 'BUSINESS', 'CLEARING', 'RECEIVER_PAYABLE', 'PLATFORM_REVENUE')
    );

ALTER TABLE accounts
    ALTER COLUMN status DROP DEFAULT;
ALTER TABLE accounts
    ALTER COLUMN status TYPE VARCHAR(20) USING status::text;
ALTER TABLE accounts
    ALTER COLUMN status SET DEFAULT 'ACTIVE';
ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'));

ALTER TABLE transactions
    ALTER COLUMN status DROP DEFAULT;
ALTER TABLE transactions
    ALTER COLUMN status TYPE VARCHAR(20) USING status::text;
ALTER TABLE transactions
    ALTER COLUMN status SET DEFAULT 'PENDING';
ALTER TABLE transactions
    ADD CONSTRAINT chk_transactions_status CHECK (
        status IN ('PENDING', 'COMPLETED', 'FAILED', 'REVERSED', 'REFUNDED', 'PARTIALLY_REFUNDED')
    );

ALTER TABLE audit_logs
    ALTER COLUMN event_type TYPE VARCHAR(50) USING event_type::text;
ALTER TABLE audit_logs
    ADD CONSTRAINT chk_audit_logs_event_type CHECK (
        event_type IN (
            'TRANSFER_INITIATED',
            'TRANSFER_COMPLETED',
            'TRANSFER_FAILED',
            'TRANSFER_REVERSED',
            'TRANSFER_REFUNDED',
            'ACCOUNT_FROZEN',
            'ACCOUNT_CLOSED'
        )
    );
