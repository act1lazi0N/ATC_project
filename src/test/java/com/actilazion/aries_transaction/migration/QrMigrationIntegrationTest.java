package com.actilazion.aries_transaction.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class QrMigrationIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void v39UpgradePreservesManualPreviewsAndConstrainsQrStateAndUniqueness() {
        flyway("39").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID owner = UUID.randomUUID(), source = UUID.randomUUID(), destination = UUID.randomUUID(), preview = UUID.randomUUID();
        jdbc.update("insert into users(id, full_name, email, password_hash) values (?, 'QR migration', ?, ?)", owner, owner + "@qr.test", UUID.randomUUID().toString());
        jdbc.update("insert into accounts(id, user_id, account_number, balance) values (?, ?, '870000000001', 5000), (?, ?, '870000000002', 0)", source, owner, destination, owner);
        jdbc.update("""
                insert into transfer_previews(id, initiator_id, source_account_id, destination_account_id, mode, amount, currency, expires_at)
                values (?, ?, ?, ?, 'OWN_ACCOUNTS', 1000, 'VND', now() + interval '5 minutes')
                """, preview, owner, source, destination);
        var latest = flyway(null); latest.migrate(); latest.validate();
        assertThat(jdbc.queryForObject("select count(*) from transfer_previews where id = ? and qr_code_id is null", Long.class, preview)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select balance from accounts where id = ?", Integer.class, source)).isEqualTo(5000);
        UUID first = UUID.randomUUID();
        insert(jdbc, first, owner, destination, "ACCOUNT", "ACTIVE", null, null, "first");
        assertThatThrownBy(() -> insert(jdbc, UUID.randomUUID(), owner, destination, "ACCOUNT", "ACTIVE", null, null, "second"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_payment_qr_active_account");
        assertThatThrownBy(() -> insert(jdbc, UUID.randomUUID(), owner, source, "ACCOUNT", "ACTIVE", null, null, "first"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_payment_qr_creation");
        assertThatThrownBy(() -> insert(jdbc, UUID.randomUUID(), owner, source, "PAYMENT_REQUEST", "ACTIVE", null, null, "missing"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_payment_qr_type");
        assertThatThrownBy(() -> insert(jdbc, UUID.randomUUID(), owner, source, "ACCOUNT", "PAID", null, null, "paid-account"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update payment_qr_codes set state = 'REVOKED' where id = ?", first))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_payment_qr_state");
        jdbc.update("update payment_qr_codes set state = 'REVOKED', revoked_at = now() where id = ?", first);
        insert(jdbc, UUID.randomUUID(), owner, destination, "ACCOUNT", "ACTIVE", null, null, "replacement");
        assertThatThrownBy(() -> jdbc.update("update transfer_previews set qr_code_id = ? where id = ?", UUID.randomUUID(), preview))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insert(JdbcTemplate jdbc, UUID id, UUID owner, UUID account, String type, String state,
                        java.math.BigDecimal amount, java.time.OffsetDateTime expiresAt, String key) {
        jdbc.update("""
                insert into payment_qr_codes(id, owner_id, account_id, type, state, amount, currency, idempotency_key, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?, 'VND', ?, now(), ?)
                """, id, owner, account, type, state, amount, key, expiresAt);
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }
}
