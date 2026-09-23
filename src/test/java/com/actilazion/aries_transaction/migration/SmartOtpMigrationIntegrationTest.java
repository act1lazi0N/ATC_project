package com.actilazion.aries_transaction.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class SmartOtpMigrationIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void upgradesPopulatedV40AndEnforcesDeviceSecretAndPurposeConstraints() {
        flyway("40").migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        var user=UUID.randomUUID();var account=UUID.randomUUID();var qr=UUID.randomUUID();
        jdbc.update("insert into users(id,full_name,email,password_hash) values (?,'OTP migration',?,?)",user,user+"@otp.test",UUID.randomUUID().toString());
        jdbc.update("insert into accounts(id,user_id,account_number,balance) values (?,?,'890000000001',5000)",account,user);
        jdbc.update("insert into payment_qr_codes(id,owner_id,account_id,type,state,currency,idempotency_key,created_at) values (?,?,?,'ACCOUNT','ACTIVE','VND',?,now())",qr,user,account,UUID.randomUUID().toString());
        var latest=flyway(null); latest.migrate(); latest.validate();
        assertThat(jdbc.queryForObject("select balance from accounts where id=?",Integer.class,account)).isEqualTo(5000);
        assertThat(jdbc.queryForObject("select state from payment_qr_codes where id=?",String.class,qr)).isEqualTo("ACTIVE");
        var device=UUID.randomUUID(); insert(jdbc,device,user,"ACTIVE","encrypted-test-value");
        assertThatThrownBy(()->insert(jdbc,UUID.randomUUID(),user,"ACTIVE","encrypted-test-value")).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_smart_otp_active");
        assertThatThrownBy(()->insert(jdbc,UUID.randomUUID(),user,"REVOKED","encrypted-test-value")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("""
                insert into smart_otp_challenges(id,user_id,credential_id,purpose,auth_version,payload_base64,state,expires_at,created_at)
                values (?,?,?,'TRANSFER',0,'e30=','PENDING',now()+interval '2 minutes',now())
                """,UUID.randomUUID(),user,device)).isInstanceOf(DataIntegrityViolationException.class);
    }
    private void insert(JdbcTemplate jdbc,UUID id,UUID user,String state,String ciphertext) {
        jdbc.update("insert into smart_otp_credentials(id,user_id,state,secret_ciphertext,key_id,auth_version,expires_at,created_at) values (?,?,?,?,'test',0,now()+interval '10 minutes',now())",id,user,state,ciphertext);
    }
    private Flyway flyway(String target) {
        var config=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).locations("classpath:db/migration");
        if(target!=null)config.target(target);return config.load();
    }
}
