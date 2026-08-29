package com.actilazion.aries_transaction.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlywayMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> CLEAN_POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final PostgreSQLContainer<?> UPGRADE_POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migrations_applyCleanlyOnPostgres() {
        Flyway flyway = Flyway.configure()
                .dataSource(CLEAN_POSTGRES.getJdbcUrl(), CLEAN_POSTGRES.getUsername(), CLEAN_POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();
        flyway.validate();
    }

    @Test
    void migrations_upgradeFromV29ToLatest_preserveExistingAccounts() throws Exception {
        Flyway previous = Flyway.configure()
                .dataSource(UPGRADE_POSTGRES.getJdbcUrl(), UPGRADE_POSTGRES.getUsername(), UPGRADE_POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("29")
                .load();
        previous.migrate();

        try (Connection connection = connection()) {
            String userId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO users (full_name, email, password_hash)
                    VALUES ('Migration User', 'migration-v29@test.local', 'hashed')
                    RETURNING id
                    """)) {
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    userId = result.getString(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO accounts (user_id, account_number)
                    VALUES (?::uuid, '290000000001')
                    """)) {
                statement.setString(1, userId);
                statement.executeUpdate();
            }
        }

        Flyway latest = Flyway.configure()
                .dataSource(UPGRADE_POSTGRES.getJdbcUrl(), UPGRADE_POSTGRES.getUsername(), UPGRADE_POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        latest.migrate();
        latest.validate();

        try (Connection connection = connection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM accounts WHERE account_number = '290000000001'"))
                    .isEqualTo(1);
            assertThat(count(connection, """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'accounts' AND column_name = 'description'
                    """))
                    .isEqualTo(1);
            assertThat(count(connection, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name IN ('account_creation_requests', 'transfer_previews')
                    """))
                    .isEqualTo(2);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                UPGRADE_POSTGRES.getJdbcUrl(),
                UPGRADE_POSTGRES.getUsername(),
                UPGRADE_POSTGRES.getPassword());
    }

    private long count(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }
}
