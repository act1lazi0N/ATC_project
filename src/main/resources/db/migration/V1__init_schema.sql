CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE user_role AS ENUM ('USER', 'ADMIN');

CREATE TYPE account_type AS ENUM ('PERSONAL', 'BUSINESS');

CREATE TYPE account_status AS ENUM ('ACTIVE', 'FROZEN', 'CLOSED');

CREATE TYPE transaction_status AS ENUM ('PENDING', 'COMPLETED', 'FAILED', 'REVERSED');

CREATE TYPE audit_event_type AS ENUM (
    'TRANSFER_INITIATED',
    'TRANSFER_COMPLETED',
    'TRANSFER_FAILED',
    'TRANSFER_REVERSED',
    'ACCOUNT_FROZEN',
    'ACCOUNT_CLOSED'
);

-- ── USERS ────────────────────────────────────────────────────
CREATE TABLE users (
                       id            UUID         NOT NULL DEFAULT gen_random_uuid(),
                       full_name     VARCHAR(100) NOT NULL,
                       email         VARCHAR(255) NOT NULL,
                       password_hash VARCHAR(255) NOT NULL,
                       role          user_role    NOT NULL DEFAULT 'USER',
                       is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
                       created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                       updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

                       CONSTRAINT pk_users PRIMARY KEY (id),
                       CONSTRAINT uk_users_email UNIQUE (email)
);

COMMENT ON TABLE  users            IS 'Người dùng hệ thống';
COMMENT ON COLUMN users.role       IS 'USER = người dùng thường, ADMIN = quản trị viên';
COMMENT ON COLUMN users.is_active  IS 'FALSE = tài khoản bị vô hiệu hóa, không thể đăng nhập';

-- ── ACCOUNTS ─────────────────────────────────────────────────
CREATE TABLE accounts (
                          id             UUID           NOT NULL DEFAULT gen_random_uuid(),
                          user_id        UUID           NOT NULL,
                          account_number VARCHAR(20)    NOT NULL,
                          account_type   account_type   NOT NULL DEFAULT 'PERSONAL',
                          balance        NUMERIC(18, 2) NOT NULL DEFAULT 0,
                          currency       CHAR(3)        NOT NULL DEFAULT 'VND',
                          status         account_status NOT NULL DEFAULT 'ACTIVE',
                          created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
                          updated_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

                          CONSTRAINT pk_accounts          PRIMARY KEY (id),
                          CONSTRAINT uk_accounts_number   UNIQUE (account_number),
                          CONSTRAINT fk_accounts_user     FOREIGN KEY (user_id) REFERENCES users(id),

                          CONSTRAINT chk_accounts_balance CHECK (balance >= 0)
);

COMMENT ON TABLE  accounts         IS 'Tài khoản ngân hàng nội bộ';
COMMENT ON COLUMN accounts.balance IS 'NUMERIC(18,2) — không dùng FLOAT tránh lỗi làm tròn';
COMMENT ON COLUMN accounts.currency IS 'ISO 4217: VND, USD, EUR...';

-- ── TRANSACTIONS ─────────────────────────────────────────────
CREATE TABLE transactions (
                              id               UUID               NOT NULL DEFAULT gen_random_uuid(),
                              from_account_id  UUID               NOT NULL,
                              to_account_id    UUID               NOT NULL,
                              initiated_by     UUID               NOT NULL,
                              amount           NUMERIC(18, 2)     NOT NULL,
                              currency         CHAR(3)            NOT NULL DEFAULT 'VND',
                              status           transaction_status NOT NULL DEFAULT 'PENDING',
                              idempotency_key  VARCHAR(64)        NOT NULL,
                              description      VARCHAR(255),
                              failure_reason   VARCHAR(500),
                              created_at       TIMESTAMPTZ        NOT NULL DEFAULT NOW(),
                              completed_at     TIMESTAMPTZ,

                              CONSTRAINT pk_transactions               PRIMARY KEY (id),
                              CONSTRAINT uk_transactions_idempotency   UNIQUE (idempotency_key),
                              CONSTRAINT fk_transactions_from_account  FOREIGN KEY (from_account_id) REFERENCES accounts(id),
                              CONSTRAINT fk_transactions_to_account    FOREIGN KEY (to_account_id)   REFERENCES accounts(id),
                              CONSTRAINT fk_transactions_initiated_by  FOREIGN KEY (initiated_by)    REFERENCES users(id),

                              CONSTRAINT chk_transactions_no_self_transfer CHECK (from_account_id <> to_account_id),
                              CONSTRAINT chk_transactions_amount CHECK (amount > 0)
);

COMMENT ON TABLE  transactions                IS 'Lịch sử giao dịch chuyển tiền';
COMMENT ON COLUMN transactions.idempotency_key IS 'UUID do client generate — ngăn chuyển tiền 2 lần khi retry';
COMMENT ON COLUMN transactions.failure_reason  IS 'Chỉ có giá trị khi status = FAILED';

-- ── AUDIT LOGS ───────────────────────────────────────────────
CREATE TABLE audit_logs (
                            id             UUID            NOT NULL DEFAULT gen_random_uuid(),
                            transaction_id UUID            NOT NULL,
                            event_type     audit_event_type NOT NULL,
                            actor_id       VARCHAR(100),
                            payload        JSONB,
                            created_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

                            CONSTRAINT pk_audit_logs         PRIMARY KEY (id),
                            CONSTRAINT fk_audit_logs_txn     FOREIGN KEY (transaction_id) REFERENCES transactions(id)
);

COMMENT ON TABLE  audit_logs         IS 'Lịch sử thay đổi trạng thái giao dịch — append only, không update';
COMMENT ON COLUMN audit_logs.payload IS 'Snapshot trạng thái tại thời điểm event — JSONB để query được';
COMMENT ON COLUMN audit_logs.actor_id IS 'Email của user hoặc "system" nếu do scheduled job';

CREATE INDEX idx_accounts_user_id ON accounts (user_id);
CREATE INDEX idx_transactions_from_account ON transactions (from_account_id);
CREATE INDEX idx_transactions_to_account   ON transactions (to_account_id);
CREATE INDEX idx_transactions_created_at ON transactions (created_at DESC);
CREATE INDEX idx_transactions_status ON transactions (status);
CREATE INDEX idx_audit_logs_transaction_id ON audit_logs (transaction_id);
CREATE INDEX idx_audit_logs_payload ON audit_logs USING GIN (payload);
CREATE OR REPLACE FUNCTION trigger_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

CREATE TRIGGER trg_accounts_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();
