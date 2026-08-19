ALTER TABLE refresh_sessions
    ADD COLUMN family_id UUID,
    ADD COLUMN revoked_reason VARCHAR(32);

WITH RECURSIVE session_families AS (
    SELECT s.id, s.id AS family_id
    FROM refresh_sessions s
    WHERE NOT EXISTS (
        SELECT 1
        FROM refresh_sessions predecessor
        WHERE predecessor.replaced_by_session_id = s.id
    )
    UNION ALL
    SELECT child.id, family.family_id
    FROM session_families family
    JOIN refresh_sessions parent ON parent.id = family.id
    JOIN refresh_sessions child ON child.id = parent.replaced_by_session_id
)
UPDATE refresh_sessions session
SET family_id = family.family_id
FROM session_families family
WHERE session.id = family.id;

UPDATE refresh_sessions
SET family_id = id
WHERE family_id IS NULL;

UPDATE refresh_sessions
SET revoked_reason = CASE
    WHEN revoked_at IS NULL THEN NULL
    WHEN replaced_by_session_id IS NOT NULL THEN 'ROTATED'
    ELSE 'ADMIN_REVOKED'
END
WHERE revoked_reason IS NULL;

ALTER TABLE refresh_sessions
    ALTER COLUMN family_id SET NOT NULL;

CREATE INDEX idx_refresh_sessions_family_id ON refresh_sessions (family_id);
