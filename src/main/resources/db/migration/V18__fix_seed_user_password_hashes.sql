UPDATE users
SET password_hash = '$2a$12$WztQTc7LCCT3xLMs9D52te6w0kG56E41g9vRDSqD5jHiGTUWUPRx2'
WHERE email IN (
    'admin@transfer.local',
    'user_a@transfer.local',
    'user_b@transfer.local',
    'user_c@transfer.local'
);
