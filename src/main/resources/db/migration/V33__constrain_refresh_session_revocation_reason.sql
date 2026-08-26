ALTER TABLE refresh_sessions
    ADD CONSTRAINT chk_refresh_sessions_revoked_reason CHECK (
        revoked_reason IS NULL OR revoked_reason IN (
            'ROTATED', 'LOGOUT', 'EXPIRED', 'SECURITY_REUSE', 'ADMIN_REVOKED'
        )
    );
