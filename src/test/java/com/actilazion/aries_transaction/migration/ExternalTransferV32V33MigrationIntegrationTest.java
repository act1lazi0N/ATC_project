package com.actilazion.aries_transaction.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ExternalTransferV32V33MigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void v31Data_upgradesWithoutPreviewFingerprint_andConstrainsRevocationReason() throws Exception {
        flyway("31").migrate();
        seedV31Data();

        Flyway latest = flyway(null);
        latest.migrate();
        latest.validate();

        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'transfer_previews'
                      AND column_name = 'request_fingerprint'
                    """))
                    .isZero();
            assertThat(text(connection, """
                    SELECT revoked_reason FROM refresh_sessions
                    WHERE refresh_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
                    """))
                    .isEqualTo("ADMIN_REVOKED");
            assertThatThrownBy(() -> execute(connection, """
                    UPDATE refresh_sessions
                    SET revoked_reason = 'INVALID_REASON'
                    WHERE refresh_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
                    """))
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("chk_refresh_sessions_revoked_reason");
        }
    }

    private void seedV31Data() throws Exception {
        try (Connection connection = connection()) {
            String userId = returningId(connection, """
                    INSERT INTO users (full_name, email, password_hash)
                    VALUES ('Migration User', 'external-v32-v33@test.local', 'hashed')
                    RETURNING id
                    """);
            String sourceId = returningId(connection, """
                    INSERT INTO accounts (user_id, account_number, balance)
                    VALUES (?::uuid, '830000000001', 5000)
                    RETURNING id
                    """, userId);
            String destinationId = returningId(connection, """
                    INSERT INTO accounts (user_id, account_number, balance)
                    VALUES (?::uuid, '830000000002', 0)
                    RETURNING id
                    """, userId);

            execute(connection, """
                    INSERT INTO transfer_previews
                        (initiator_id, source_account_id, destination_account_id, mode, amount, fee,
                         currency, request_fingerprint, expires_at)
                    VALUES (?::uuid, ?::uuid, ?::uuid, 'EXTERNAL', 1000, 0,
                            'VND', 'migration-fingerprint', NOW() + INTERVAL '5 minutes')
                    """, userId, sourceId, destinationId);
            execute(connection, """
                    INSERT INTO refresh_sessions
                        (user_id, refresh_token_hash, expires_at, family_id, revoked_at, revoked_reason)
                    VALUES (?::uuid,
                            'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                            NOW() + INTERVAL '1 day', gen_random_uuid(), NOW(), 'ADMIN_REVOKED')
                    """, userId);
        }
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private String returningId(Connection connection, String sql, String... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setValues(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private void execute(Connection connection, String sql, String... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setValues(statement, values);
            statement.executeUpdate();
        }
    }

    private void setValues(PreparedStatement statement, String... values) throws Exception {
        for (int index = 0; index < values.length; index++) {
            statement.setString(index + 1, values[index]);
        }
    }

    private long count(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private String text(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
