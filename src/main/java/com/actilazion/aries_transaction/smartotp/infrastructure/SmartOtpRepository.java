package com.actilazion.aries_transaction.smartotp.infrastructure;

import com.actilazion.aries_transaction.smartotp.domain.OtpPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

/** All mutations are serialized by the owning users row, including cleanup. */
@Repository
@RequiredArgsConstructor
public class SmartOtpRepository {
    private final JdbcTemplate jdbc;
    public record Profile(boolean everEnrolled, OffsetDateTime blockedUntil, String grantHash,
                          OffsetDateTime grantExpiresAt, Long grantAuthVersion) {
        @Override public String toString() { return "SmartOtpProfile[redacted]"; }
    }
    public record Credential(UUID id, UUID userId, String state, String ciphertext, String keyId,
                             long authVersion, OffsetDateTime expiresAt) {
        @Override public String toString() { return "SmartOtpCredential[id=" + id + "]"; }
    }
    public record Challenge(UUID id, UUID userId, UUID credentialId, OtpPurpose purpose, long authVersion,
                            UUID previewId, String idempotencyKey, String bindingHash, String payloadBase64,
                            String state, int failedAttempts, OffsetDateTime expiresAt) {
        @Override public String toString() { return "SmartOtpChallenge[id=" + id + ",state=" + state + "]"; }
    }
    public Profile profile(UUID user) {
        return jdbc.query("select * from smart_otp_profiles where user_id = ?", (r, n) ->
                new Profile(r.getBoolean("ever_enrolled"), time(r, "blocked_until"), r.getString("grant_hash"),
                        time(r, "grant_expires_at"), r.getObject("grant_auth_version", Long.class)), user)
                .stream().findFirst().orElse(new Profile(false, null, null, null, null));
    }
    public void ensureProfile(UUID user) {
        jdbc.update("insert into smart_otp_profiles(user_id) values (?) on conflict do nothing", user);
    }
    public Optional<Credential> active(UUID user) {
        return jdbc.query("select * from smart_otp_credentials where user_id = ? and state = 'ACTIVE' for update",
                (r, n) -> credential(r), user).stream().findFirst();
    }
    public Optional<Credential> credential(UUID id, UUID user) {
        return jdbc.query("select * from smart_otp_credentials where id = ? and user_id = ? for update",
                (r, n) -> credential(r), id, user).stream().findFirst();
    }
    public void insert(Credential c, OffsetDateTime now) {
        jdbc.update("""
                insert into smart_otp_credentials(id,user_id,state,secret_ciphertext,key_id,auth_version,expires_at,created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, c.id(), c.userId(), c.state(), c.ciphertext(), c.keyId(), c.authVersion(), c.expiresAt(), now);
    }
    public void revoke(UUID user, String state) {
        jdbc.update("""
                update smart_otp_challenges set state = 'REVOKED' where user_id = ? and state <> 'CONSUMED'
                and credential_id in (select id from smart_otp_credentials where user_id = ? and state = ?)
                """, user, user, state);
        jdbc.update("update smart_otp_credentials set state = 'REVOKED', secret_ciphertext = null where user_id = ? and state = ?", user, state);
    }
    public void activate(Credential c) {
        jdbc.update("update smart_otp_credentials set state = 'ACTIVE' where id = ? and state = 'PENDING'", c.id());
        jdbc.update("update smart_otp_profiles set ever_enrolled = true where user_id = ?", c.userId());
    }
    public Optional<Challenge> challenge(UUID id, UUID user) {
        return jdbc.query("select * from smart_otp_challenges where id = ? and user_id = ? for update",
                (r, n) -> challenge(r), id, user).stream().findFirst();
    }
    public Optional<UUID> challengeCredential(UUID id, UUID user) {
        return jdbc.query("select credential_id from smart_otp_challenges where id = ? and user_id = ?",
                (r, n) -> r.getObject(1, UUID.class), id, user).stream().findFirst();
    }
    public Optional<Challenge> forPreview(UUID preview, UUID user) {
        return jdbc.query("select * from smart_otp_challenges where preview_id = ? and user_id = ? for update",
                (r, n) -> challenge(r), preview, user).stream().findFirst();
    }
    public void insert(Challenge c, OffsetDateTime now) {
        jdbc.update("""
                insert into smart_otp_challenges(id,user_id,credential_id,purpose,auth_version,preview_id,
                idempotency_key,binding_hash,payload_base64,state,failed_attempts,expires_at,created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, c.id(), c.userId(), c.credentialId(), c.purpose().name(), c.authVersion(), c.previewId(),
                c.idempotencyKey(), c.bindingHash(), c.payloadBase64(), c.state(), c.failedAttempts(), c.expiresAt(), now);
    }
    public void state(UUID id, String state) { jdbc.update("update smart_otp_challenges set state = ? where id = ?", state, id); }
    public void failed(UUID user, UUID challenge, OffsetDateTime now) {
        if (challenge != null) jdbc.update("update smart_otp_challenges set failed_attempts = failed_attempts + 1 where id = ?", challenge);
        jdbc.update("insert into smart_otp_failures(user_id, failed_at) values (?, ?)", user, now);
        Integer failures = jdbc.queryForObject("select count(*) from smart_otp_failures where user_id = ? and failed_at > ?",
                Integer.class, user, now.minusMinutes(15));
        if (failures != null && failures >= 10)
            jdbc.update("update smart_otp_profiles set blocked_until = ? where user_id = ?", now.plusMinutes(15), user);
    }
    public void replaceCodes(UUID user, List<String> hashes) {
        jdbc.update("delete from smart_otp_recovery_codes where user_id = ?", user);
        for (String hash : hashes) jdbc.update("insert into smart_otp_recovery_codes(id,user_id,code_hash) values (?, ?, ?)", UUID.randomUUID(), user, hash);
    }
    public boolean consumeCode(UUID user, String hash, OffsetDateTime now) {
        return jdbc.update("update smart_otp_recovery_codes set consumed_at = ? where user_id = ? and code_hash = ? and consumed_at is null", now, user, hash) == 1;
    }
    public void grant(UUID user, String hash, OffsetDateTime expires, long authVersion) {
        jdbc.update("update smart_otp_profiles set grant_hash = ?, grant_expires_at = ?, grant_auth_version = ? where user_id = ?", hash, expires, authVersion, user);
    }
    public void clearGrant(UUID user) {
        jdbc.update("update smart_otp_profiles set grant_hash = null, grant_expires_at = null, grant_auth_version = null where user_id = ?", user);
    }
    public List<UUID> cleanupUsers(OffsetDateTime now) {
        return jdbc.query("""
                select user_id from smart_otp_credentials where state = 'PENDING' and expires_at <= ?
                union select user_id from smart_otp_profiles where grant_expires_at <= ?
                union select user_id from smart_otp_failures where failed_at <= ? limit 100
                """, (r,n) -> r.getObject(1, UUID.class), now, now, now.minusMinutes(15));
    }
    public void cleanup(UUID user, OffsetDateTime now) {
        jdbc.update("""
                update smart_otp_credentials set state = 'REVOKED', secret_ciphertext = null
                where user_id = ? and state = 'PENDING' and expires_at <= ?
                """, user, now);
        jdbc.update("""
                update smart_otp_profiles set grant_hash = null, grant_expires_at = null, grant_auth_version = null
                where user_id = ? and grant_expires_at <= ?
                """, user, now);
        jdbc.update("delete from smart_otp_failures where user_id = ? and failed_at <= ?", user, now.minusMinutes(15));
    }
    private static Credential credential(ResultSet r) throws SQLException {
        return new Credential(r.getObject("id", UUID.class), r.getObject("user_id", UUID.class), r.getString("state"),
                r.getString("secret_ciphertext"), r.getString("key_id"), r.getLong("auth_version"), time(r,"expires_at"));
    }
    private static Challenge challenge(ResultSet r) throws SQLException {
        return new Challenge(r.getObject("id", UUID.class), r.getObject("user_id", UUID.class), r.getObject("credential_id", UUID.class),
                OtpPurpose.valueOf(r.getString("purpose")), r.getLong("auth_version"), r.getObject("preview_id", UUID.class),
                r.getString("idempotency_key"), r.getString("binding_hash"), r.getString("payload_base64"),
                r.getString("state"), r.getInt("failed_attempts"), time(r,"expires_at"));
    }
    private static OffsetDateTime time(ResultSet r, String column) throws SQLException { return r.getObject(column, OffsetDateTime.class); }
}
