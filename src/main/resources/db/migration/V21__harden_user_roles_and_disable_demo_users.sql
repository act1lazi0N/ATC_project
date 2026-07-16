ALTER TABLE users
    DROP CONSTRAINT IF EXISTS chk_users_role;

ALTER TABLE users
    ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'MERCHANT', 'OPERATOR', 'ADMIN'));

UPDATE users
SET is_active = FALSE
WHERE email IN (
    'admin@transfer.local',
    'user_a@transfer.local',
    'user_b@transfer.local',
    'user_c@transfer.local'
);
