package com.actilazion.aries_transaction.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class AccountSecurityMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void upgradeFromV38PreservesCredentialsAndAddsRevocationAndDeliveryConstraints() throws Exception {
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").target("38").load().migrate();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            try (var sql = connection.prepareStatement("""
                    INSERT INTO users(id, full_name, email, password_hash)
                    VALUES (?, 'Upgrade', 'upgrade-account-security@test.local', 'existing-password-hash')
                    """)) {
                sql.setObject(1, userId);
                sql.executeUpdate();
            }
            try (var sql = connection.prepareStatement("""
                    INSERT INTO refresh_sessions(id, user_id, refresh_token_hash, family_id, expires_at)
                    VALUES (?, ?, 'existing-refresh-hash', ?, NOW() + INTERVAL '1 day')
                    """)) {
                sql.setObject(1, sessionId);
                sql.setObject(2, userId);
                sql.setObject(3, sessionId);
                sql.executeUpdate();
            }
        }
        var latest = Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").load();
        latest.migrate();
        latest.validate();
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            try (var sql = connection.prepareStatement("SELECT auth_version, password_hash FROM users WHERE id = ?")) {
                sql.setObject(1, userId);
                try (var result = sql.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong("auth_version")).isZero();
                    assertThat(result.getString("password_hash")).isEqualTo("existing-password-hash");
                }
            }
            try (var sql = connection.prepareStatement("SELECT refresh_token_hash, revoked_at FROM refresh_sessions WHERE id = ?")) {
                sql.setObject(1, sessionId);
                try (var result = sql.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("refresh_token_hash")).isEqualTo("existing-refresh-hash");
                    assertThat(result.getObject("revoked_at")).isNull();
                }
            }
            UUID challenge = UUID.randomUUID();
            try (var sql = connection.prepareStatement("""
                    INSERT INTO password_reset_challenges(id, user_id, email, expires_at)
                    VALUES (?, ?, 'upgrade-account-security@test.local', NOW() + INTERVAL '15 minutes')
                    """)) {
                sql.setObject(1, challenge);
                sql.setObject(2, userId);
                sql.executeUpdate();
                sql.setObject(1, UUID.randomUUID());
                assertThatThrownBy(sql::executeUpdate)
                        .isInstanceOf(SQLException.class).hasMessageContaining("uk_password_reset_active_user");
            }
            try (var sql = connection.prepareStatement("""
                    INSERT INTO email_deliveries(id, purpose, password_reset_challenge_id)
                    VALUES (?, ?, ?)
                    """)) {
                sql.setObject(1, UUID.randomUUID());
                sql.setString(2, "PASSWORD_RESET");
                sql.setObject(3, challenge);
                sql.executeUpdate();
                sql.setObject(1, UUID.randomUUID());
                sql.setString(2, "PASSWORD_CHANGED");
                assertThatThrownBy(sql::executeUpdate)
                        .isInstanceOf(SQLException.class).hasMessageContaining("chk_email_deliveries_target");
            }
            try (var sql = connection.prepareStatement("UPDATE users SET auth_version = -1 WHERE id = ?")) {
                sql.setObject(1, userId);
                assertThatThrownBy(sql::executeUpdate)
                        .isInstanceOf(SQLException.class).hasMessageContaining("chk_users_auth_version");
            }
            try (var sql = connection.prepareStatement("""
                    UPDATE refresh_sessions SET revoked_at = NOW(), revoked_reason = ? WHERE id = ?
                    """)) {
                sql.setObject(2, sessionId);
                for (String reason : new String[]{"PASSWORD_CHANGED", "PASSWORD_RESET", "LOGOUT_ALL"}) {
                    sql.setString(1, reason);
                    sql.executeUpdate();
                }
            }
        }
    }
}
