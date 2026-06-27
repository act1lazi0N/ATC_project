-- ============================================================
-- V2__seed_data.sql
-- Dữ liệu mẫu cho môi trường dev/local
--
-- QUAN TRỌNG: File này chỉ chạy trên môi trường dev.
-- Production dùng Flyway locations tách biệt:
--   spring.flyway.locations=classpath:db/migration,classpath:db/seed
-- Và chỉ include db/seed ở profile dev.
--
-- Password cho tất cả tài khoản mẫu: "password123"
-- BCrypt hash với cost factor 12
-- ============================================================

-- ── ADMIN USER ───────────────────────────────────────────────
INSERT INTO users (id, full_name, email, password_hash, role, is_active)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'System Admin',
    'admin@transfer.local',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj2NJdNxMYZO',
    'ADMIN',
    TRUE
);

-- ── DEMO USERS ───────────────────────────────────────────────
INSERT INTO users (id, full_name, email, password_hash, role, is_active)
VALUES
    (
        '00000000-0000-0000-0000-000000000002',
        'Nguyen Van A',
        'user_a@transfer.local',
        '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj2NJdNxMYZO',
        'USER',
        TRUE
    ),
    (
        '00000000-0000-0000-0000-000000000003',
        'Tran Thi B',
        'user_b@transfer.local',
        '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj2NJdNxMYZO',
        'USER',
        TRUE
    ),
    (
        '00000000-0000-0000-0000-000000000004',
        'Le Van C',
        'user_c@transfer.local',
        '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj2NJdNxMYZO',
        'USER',
        TRUE
    );

-- ── DEMO ACCOUNTS ────────────────────────────────────────────
INSERT INTO accounts (id, user_id, account_number, account_type, balance, currency, status)
VALUES
    -- User A: 2 tài khoản
    (
        '10000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000002',
        '100000000001',
        'PERSONAL',
        50000000.00,   -- 50 triệu VND
        'VND',
        'ACTIVE'
    ),
    (
        '10000000-0000-0000-0000-000000000002',
        '00000000-0000-0000-0000-000000000002',
        '100000000002',
        'BUSINESS',
        200000000.00,  -- 200 triệu VND
        'VND',
        'ACTIVE'
    ),
    -- User B: 1 tài khoản
    (
        '10000000-0000-0000-0000-000000000003',
        '00000000-0000-0000-0000-000000000003',
        '100000000003',
        'PERSONAL',
        10000000.00,   -- 10 triệu VND
        'VND',
        'ACTIVE'
    ),
    -- User C: 1 tài khoản bị frozen — để test case
    (
        '10000000-0000-0000-0000-000000000004',
        '00000000-0000-0000-0000-000000000004',
        '100000000004',
        'PERSONAL',
        5000000.00,
        'VND',
        'FROZEN'
    );
