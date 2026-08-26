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
class ExternalTransferV31MigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void v30Data_upgradesToV31WithNormalizedPreviewsAndImmutableAccountSnapshot() throws Exception {
        flyway("30").migrate();
        seedV30Data();

        Flyway latest = flyway(null);
        latest.migrate();
        latest.validate();

        try (Connection connection = connection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM transfer_previews WHERE amount < 1000"))
                    .isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM transfer_previews WHERE amount = 1000"))
                    .isEqualTo(1);
            assertThat(text(connection, """
                    SELECT request_hash FROM account_creation_requests
                    WHERE idempotency_key = 'migration-account-key'
                    """))
                    .isEqualTo("c6dcc98969894a3d1f09213dd85c768f9af53614a184a843cebe291ca4fdb4f6");
            assertThat(text(connection, """
                    SELECT response_payload ->> 'accountNumber' FROM account_creation_requests
                    WHERE idempotency_key = 'migration-account-key'
                    """))
                    .isEqualTo("810000000001");
            assertThat(text(connection, """
                    SELECT response_payload ->> 'description' FROM account_creation_requests
                    WHERE idempotency_key = 'migration-account-key'
                    """))
                    .isEqualTo("Đầu tư");
            assertThat(text(connection, """
                    SELECT response_payload ->> 'createdAt' FROM account_creation_requests
                    WHERE idempotency_key = 'migration-account-key'
                    """))
                    .endsWith("Z");

            assertThatThrownBy(() -> insertInvalidPreview(connection))
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("chk_transfer_previews_amount");
            assertThatThrownBy(() -> insertRequestWithoutSnapshot(connection))
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("response_payload");
        }
    }

    private void seedV30Data() throws Exception {
        try (Connection connection = connection()) {
            String userId = returningId(connection, """
                    INSERT INTO users (full_name, email, password_hash)
                    VALUES ('Migration User', 'external-v31@test.local', 'hashed')
                    RETURNING id
                    """);
            String sourceId = returningId(connection, """
                    INSERT INTO accounts (user_id, account_number, balance, description)
                    VALUES (?::uuid, '810000000001', 5000, 'Đầu tư')
                    RETURNING id
                    """, userId);
            String destinationId = returningId(connection, """
                    INSERT INTO accounts (user_id, account_number, balance)
                    VALUES (?::uuid, '810000000002', 0)
                    RETURNING id
                    """, userId);

            execute(connection, """
                    INSERT INTO account_creation_requests
                        (user_id, idempotency_key, request_hash, account_id)
                    VALUES (?::uuid, 'migration-account-key', 'legacy32', ?::uuid)
                    """, userId, sourceId);
            execute(connection, v30PreviewInsertSql(), userId, sourceId, destinationId, "10");
            execute(connection, v30PreviewInsertSql(), userId, sourceId, destinationId, "1000");
        }
    }

    private void insertInvalidPreview(Connection connection) throws Exception {
        String userId = text(connection, "SELECT id::text FROM users WHERE email = 'external-v31@test.local'");
        String sourceId = text(connection, "SELECT id::text FROM accounts WHERE account_number = '810000000001'");
        String destinationId = text(connection, "SELECT id::text FROM accounts WHERE account_number = '810000000002'");
        execute(connection, latestPreviewInsertSql(), userId, sourceId, destinationId, "999");
    }

    private void insertRequestWithoutSnapshot(Connection connection) throws Exception {
        String userId = text(connection, "SELECT id::text FROM users WHERE email = 'external-v31@test.local'");
        String accountId = text(connection, "SELECT id::text FROM accounts WHERE account_number = '810000000002'");
        execute(connection, """
                INSERT INTO account_creation_requests
                    (user_id, idempotency_key, request_hash, account_id)
                VALUES (?::uuid, 'missing-snapshot-key',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', ?::uuid)
                """, userId, accountId);
    }

    private String v30PreviewInsertSql() {
        return """
                INSERT INTO transfer_previews
                    (initiator_id, source_account_id, destination_account_id, mode, amount, fee,
                     currency, request_fingerprint, expires_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, 'EXTERNAL', ?::numeric, 0,
                        'VND', 'migration-fingerprint', NOW() + INTERVAL '5 minutes')
                """;
    }

    private String latestPreviewInsertSql() {
        return """
                INSERT INTO transfer_previews
                    (initiator_id, source_account_id, destination_account_id, mode, amount, fee,
                     currency, expires_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, 'EXTERNAL', ?::numeric, 0,
                        'VND', NOW() + INTERVAL '5 minutes')
                """;
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
