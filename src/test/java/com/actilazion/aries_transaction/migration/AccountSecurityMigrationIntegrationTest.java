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
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
             var sql = connection.createStatement()) {
            sql.execute("INSERT INTO users(id, full_name, email, password_hash) VALUES ('" + userId
                    + "', 'Upgrade', 'upgrade-account-security@test.local', 'existing-password-hash')");
            sql.execute("INSERT INTO refresh_sessions(id, user_id, refresh_token_hash, family_id, expires_at) VALUES ('"
                    + sessionId + "', '" + userId + "', 'existing-refresh-hash', '" + sessionId + "', NOW() + INTERVAL '1 day')");
        }
        var latest = Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").load();
        latest.migrate();
        latest.validate();
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
             var sql = connection.createStatement()) {
            try (var result = sql.executeQuery("SELECT auth_version, password_hash FROM users WHERE id = '" + userId + "'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("auth_version")).isZero();
                assertThat(result.getString("password_hash")).isEqualTo("existing-password-hash");
            }
            try (var result = sql.executeQuery("SELECT refresh_token_hash, revoked_at FROM refresh_sessions WHERE id = '" + sessionId + "'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("refresh_token_hash")).isEqualTo("existing-refresh-hash");
                assertThat(result.getObject("revoked_at")).isNull();
            }
            UUID challenge = UUID.randomUUID();
            sql.execute("INSERT INTO password_reset_challenges(id, user_id, email, expires_at) VALUES ('" + challenge
                    + "', '" + userId + "', 'upgrade-account-security@test.local', NOW() + INTERVAL '15 minutes')");
            assertThatThrownBy(() -> sql.execute("INSERT INTO password_reset_challenges(id, user_id, email, expires_at) VALUES ('"
                    + UUID.randomUUID() + "', '" + userId + "', 'upgrade-account-security@test.local', NOW() + INTERVAL '15 minutes')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uk_password_reset_active_user");
            sql.execute("INSERT INTO email_deliveries(id, purpose, password_reset_challenge_id) VALUES ('"
                    + UUID.randomUUID() + "', 'PASSWORD_RESET', '" + challenge + "')");
            assertThatThrownBy(() -> sql.execute("INSERT INTO email_deliveries(id, purpose, password_reset_challenge_id) VALUES ('"
                    + UUID.randomUUID() + "', 'PASSWORD_CHANGED', '" + challenge + "')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("chk_email_deliveries_target");
            assertThatThrownBy(() -> sql.execute("UPDATE users SET auth_version = -1 WHERE id = '" + userId + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("chk_users_auth_version");
            for (String reason : new String[]{"PASSWORD_CHANGED", "PASSWORD_RESET", "LOGOUT_ALL"}) {
                sql.execute("UPDATE refresh_sessions SET revoked_at = NOW(), revoked_reason = '" + reason + "' WHERE id = '" + sessionId + "'");
            }
        }
    }
}
