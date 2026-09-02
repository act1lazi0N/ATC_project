ALTER TABLE users
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE identity_audit_logs
    ADD COLUMN actor_user_id UUID;

CREATE INDEX idx_users_customer_search
    ON users (role, is_active, created_at DESC, id DESC);

CREATE INDEX idx_users_lower_email
    ON users (LOWER(email));

CREATE INDEX idx_users_lower_full_name
    ON users (LOWER(full_name));

CREATE INDEX idx_identity_audit_actor_created
    ON identity_audit_logs (actor_user_id, created_at DESC);

CREATE INDEX idx_ledger_entries_forensic_cursor
    ON ledger_entries (created_at DESC, id DESC);

CREATE INDEX idx_ledger_entries_forensic_filters
    ON ledger_entries (currency, entry_type, direction, created_at DESC, id DESC);

CREATE INDEX idx_transactions_operations_cursor
    ON transactions (created_at DESC, id DESC);
