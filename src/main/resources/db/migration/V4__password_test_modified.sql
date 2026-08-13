-- Generate hash đúng bằng BCrypt cost 12 cho "password123"
UPDATE users SET password_hash = '$2a$12$mLw64s78D1wld3jjsmwgI.HbKUyE.WevvQS8dZ8eLrb5tguiDFV3a'
WHERE email IN (
                'admin@transfer.local',
                'user_a@transfer.local',
                'user_b@transfer.local',
                'user_c@transfer.local'
    );
