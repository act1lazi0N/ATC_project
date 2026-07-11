package com.actilazion.aries_transaction.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

class FlywayMigrationIntegrationTest {

    @Test
    void migrations_applyCleanlyOnPostgres() throws IOException {
        Properties env = loadDotEnv();
        String database = "aries_transaction_migration_test";
        String username = env.getProperty("POSTGRES_USER", "transfer_user");
        String password = env.getProperty("POSTGRES_PASSWORD", "transfer_pass");
        recreateDatabase(database, username, password);

        Flyway flyway = Flyway.configure()
                .dataSource("jdbc:postgresql://localhost:5432/" + database, username, password)
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();
        flyway.validate();
    }

    private void recreateDatabase(String database, String username, String password) {
        try (var connection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/postgres", username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database);
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not recreate migration test database", exception);
        }
    }

    private Properties loadDotEnv() throws IOException {
        Properties properties = new Properties();
        Path dotEnv = Path.of(".env");
        if (Files.exists(dotEnv)) {
            try (InputStream inputStream = Files.newInputStream(dotEnv)) {
                properties.load(inputStream);
            }
        }
        System.getenv().forEach(properties::putIfAbsent);
        return properties;
    }
}
