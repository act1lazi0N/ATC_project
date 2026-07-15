UPDATE users
SET is_active = FALSE
WHERE email IN (
    'admin@transfer.local',
    'user_a@transfer.local',
    'user_b@transfer.local',
    'user_c@transfer.local'
);
