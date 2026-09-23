package com.actilazion.aries_transaction.smartotp;

import com.actilazion.aries_transaction.account.domain.*;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.config.JwtService;
import com.actilazion.aries_transaction.identity.application.*;
import com.actilazion.aries_transaction.identity.domain.*;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.smartotp.application.*;
import com.actilazion.aries_transaction.smartotp.domain.*;
import com.actilazion.aries_transaction.smartotp.dto.SmartOtpDtos.*;
import com.actilazion.aries_transaction.notification.application.EmailDeliveryService;
import com.actilazion.aries_transaction.payment.application.PaymentQrService;
import com.actilazion.aries_transaction.payment.domain.QrType;
import com.actilazion.aries_transaction.payment.dto.CreateQrRequest;
import com.actilazion.aries_transaction.transaction.application.*;
import com.actilazion.aries_transaction.transaction.domain.TransferPreviewMode;
import com.actilazion.aries_transaction.transaction.dto.*;
import com.actilazion.aries_transaction.transaction.infrastructure.TransferPreviewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SmartOtpPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Container static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.27").withExposedPorts(1025,8025);
    private static final String ENCRYPTION_KEY = randomKey();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("app.smart-otp.mode", () -> "ENFORCED");
        r.add("app.smart-otp.key-id", () -> "integration-test");
        r.add("app.smart-otp.encryption-key", () -> ENCRYPTION_KEY);
        r.add("security.ephemeral.enabled", () -> true);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("app.notification.email.mode", () -> "smtp");
        r.add("app.notification.email.from", () -> "no-reply@aries.test");
        r.add("spring.mail.host", MAILPIT::getHost);
        r.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        r.add("spring.mail.properties.mail.smtp.auth", () -> false);
        r.add("spring.mail.properties.mail.smtp.starttls.enable", () -> false);
        r.add("spring.mail.properties.mail.smtp.starttls.required", () -> false);
    }
    @Autowired SmartOtpLifecycleService lifecycle;
    @Autowired SmartOtpChallengeService challenges;
    @Autowired SmartOtpVerificationService verification;
    @Autowired SmartOtpCleanup cleanup;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired TransferPreviewService previews;
    @Autowired TransferService transfers;
    @Autowired TransferPreviewRepository previewRepository;
    @Autowired PaymentQrService qr;
    @Autowired SessionRevocationService sessions;
    @Autowired EmailDeliveryService email;
    @Autowired com.actilazion.aries_transaction.notification.infrastructure.email.EmailGateway gateway;
    @Autowired SmartOtpProperties otpProperties;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired JwtService jwt;
    @LocalServerPort int port;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private static final AtomicLong NUMBERS = new AtomicLong(880_000_000_000L);

    @Test void enrollmentRequiresPasswordVerifiedEmailAndProofAndKeepsSecretsPrivate() throws Exception {
        var f = person(false);
        assertThatThrownBy(() -> lifecycle.enroll(p(f), f.password(), null, null)).hasMessageContaining("email verification required");
        jdbc.update("update users set email_verified_at = now() where id = ?", f.user().getId());
        assertThatThrownBy(() -> lifecycle.enroll(p(f), "incorrect", null, null)).hasMessageContaining("current password invalid");
        var e = lifecycle.enroll(p(f), f.password(), null, null);
        assertThatThrownBy(() -> lifecycle.confirm(p(f), e.id(), e.challenge().id())).hasMessageContaining("authorization required");
        prove(f, e.challenge(), e.secretBase64());
        var codes = lifecycle.confirm(p(f), e.id(), e.challenge().id());
        assertThat(codes.recoveryCodes()).hasSize(10).doesNotHaveDuplicates();
        assertThat(lifecycle.status(p(f)).enrollmentState()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select secret_ciphertext from smart_otp_credentials where id = ?", String.class, e.id()))
                .doesNotContain(e.secretBase64());
        var hashes = jdbc.queryForList("select code_hash from smart_otp_recovery_codes where user_id = ?", String.class, f.user().getId());
        assertThat(hashes).doesNotContainAnyElementsOf(codes.recoveryCodes());
        assertThat(e.toString()).doesNotContain(e.secretBase64());
        assertThatThrownBy(() -> lifecycle.enroll(p(f), f.password(), null, null)).hasMessageContaining("authorization required");
        UUID delivery = jdbc.queryForObject("""
                select d.id from email_deliveries d join identity_audit_logs a on a.id=d.security_audit_event_id
                where a.user_id = ? and d.purpose = 'SMART_OTP_SECURITY'
                """, UUID.class, f.user().getId());
        var work = email.claim(delivery).orElseThrow();
        assertThat(work.message().subject()).contains("Smart OTP");
        assertThat(work.message().toString()).doesNotContain(e.secretBase64());
        gateway.send(work.message()); email.markDelivered(work, 1);
        var mail = http.send(HttpRequest.newBuilder(URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1/messages")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(mail.statusCode()).isEqualTo(200);
        assertThat(mail.body()).contains(f.user().getEmail(), "Aries Smart OTP security update").doesNotContain(e.secretBase64());
    }

    @Test void transferAuthorizationIsBoundToSnapshotKeyAndPurposeAndPreservesOriginalReplay() {
        var f = fixture(); var e = enrolled(f.person()); var key = key();
        var preview = preview(f);
        assertThat(preview.authorizationRequirement()).isEqualTo("SMART_OTP");
        var c = challenges.authorize(p(f.person()), preview.previewId(), key);
        assertThat(challenges.authorize(p(f.person()), preview.previewId(), key).id()).isEqualTo(c.id());
        assertThatThrownBy(() -> challenges.authorize(p(f.person()), preview.previewId(), key())).hasMessageContaining("binding conflict");
        String payload = new String(Base64.getDecoder().decode(c.payloadBase64()), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).doesNotContain(f.destination().getId().toString()).contains("1500.00");
        prove(f.person(), c, e.secretBase64());
        var request = new TransferExecuteRequest(preview.previewId(), key, c.id());
        assertThatThrownBy(() -> transfers.execute(new TransferExecuteRequest(preview.previewId(), key(), c.id()), f.person().user().getEmail()))
                .hasMessageContaining("binding conflict");
        jdbc.update("update transfer_previews set description = 'changed' where id = ?", preview.previewId());
        assertThatThrownBy(() -> transfers.execute(request, f.person().user().getEmail())).hasMessageContaining("binding conflict");
        jdbc.update("update transfer_previews set description = 'Lunch' where id = ?", preview.previewId());
        var tx = transfers.execute(request, f.person().user().getEmail());
        assertEffect(f, tx.id());
        jdbc.update("update smart_otp_challenges set created_at=now()-interval '3 days', expires_at=now()-interval '2 days' where id=?", c.id());
        jdbc.update("update transfer_previews set expires_at=now()-interval '2 days' where id=?", preview.previewId());
        new TransactionTemplate(manager).executeWithoutResult(s -> previewRepository.deleteExpiredBefore(OffsetDateTime.now().minusDays(1)));
        assertThat(transfers.execute(new TransferExecuteRequest(preview.previewId(), key), f.person().user().getEmail())).isEqualTo(tx);
        assertThat(challenges.read(p(f.person()), c.id()).state()).isEqualTo("CONSUMED");
    }

    @Test void wrongCodeCountersCommitAndNewChallengesDoNotResetTheUserLimit() throws Exception {
        var f = person(true); var e = enrolled(f);
        for (int round=0; round<2; round++) {
            var c = challenges.management(p(f), OtpPurpose.REPLACE_DEVICE);
            String wrong = wrong(e.secretBase64(), c);
            for (int attempt=0; attempt<5; attempt++) {
                var outcome = verification.verify(p(f), c.id(), c.purpose(), wrong);
                assertThatThrownBy(outcome::requireSuccess).hasMessageContaining("smart otp invalid");
            }
            assertThat(jdbc.queryForObject("select failed_attempts from smart_otp_challenges where id=?", Integer.class, c.id())).isEqualTo(5);
            assertThat(challenges.read(p(f), c.id()).state()).isEqualTo("LOCKED");
        }
        var next = challenges.management(p(f), OtpPurpose.REPLACE_DEVICE);
        REDIS.getDockerClient().restartContainerCmd(REDIS.getContainerId()).exec();
        assertThat(verification.verify(p(f), next.id(), next.purpose(), code(e.secretBase64(), next)).error()).isEqualTo("SMART_OTP_RATE_LIMITED");
        assertThat(jdbc.queryForObject("select count(*) from smart_otp_failures where user_id=?", Integer.class, f.user().getId())).isEqualTo(10);
    }

    @Test void rollbackLeavesVerifiedAuthorizationAndAllMoneyEffectsUntouched() {
        var f = fixture(); var e = enrolled(f.person()); var preview = preview(f); var key = key();
        var c = challenges.authorize(p(f.person()), preview.previewId(), key); prove(f.person(), c, e.secretBase64());
        var request = new TransferExecuteRequest(preview.previewId(), key, c.id());
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(s -> {
            transfers.execute(request, f.person().user().getEmail()); throw new IllegalStateException("injected rollback");
        })).hasMessageContaining("injected rollback");
        assertThat(balance(f.source())).isEqualByComparingTo("5000");
        assertThat(balance(f.destination())).isEqualByComparingTo("0");
        assertThat(challenges.read(p(f.person()), c.id()).state()).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("select count(*) from transactions where idempotency_key=?", Integer.class, key)).isZero();
        assertEffect(f, transfers.execute(request, f.person().user().getEmail()).id());
    }

    @Test void concurrentVerificationAndExecuteProduceOneFinancialEffect() throws Exception {
        var f = fixture(); var e = enrolled(f.person()); var preview = preview(f); var key = key();
        var c = challenges.authorize(p(f.person()), preview.previewId(), key);
        var verified = race(() -> verification.verify(p(f.person()), c.id(), OtpPurpose.TRANSFER, code(e.secretBase64(), c)),
                () -> verification.verify(p(f.person()), c.id(), OtpPurpose.TRANSFER, code(e.secretBase64(), c)));
        assertThat(verified).allMatch(o -> o instanceof SmartOtpVerificationService.Outcome v && v.error() == null);
        var request = new TransferExecuteRequest(preview.previewId(), key, c.id());
        var results = race(() -> transfers.execute(request, f.person().user().getEmail()), () -> transfers.execute(request, f.person().user().getEmail()));
        assertThat(results).allMatch(TransactionResponse.class::isInstance);
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertEffect(f, ((TransactionResponse)results.get(0)).id());
    }

    @Test void legacyExternalIsBlockedButBackendConfirmedOwnAccountsAreExempt() {
        var f = fixture();
        assertThatThrownBy(() -> transfers.transfer(new TransferRequest(f.source().getId().toString(), f.destination().getId().toString(),
                new BigDecimal("1500"), key(), "VND", "legacy"), f.person().user().getEmail())).hasMessageContaining("authorization required");
        var own = account(f.person().user(), "0");
        var p = previews.create(new TransferPreviewRequest(TransferPreviewMode.OWN_ACCOUNTS, f.source().getId(), own.getId(), null, "1500", "VND", "own"), f.person().user().getEmail());
        assertThat(p.authorizationRequirement()).isEqualTo("NONE");
        transfers.execute(new TransferExecuteRequest(p.previewId(), key()), f.person().user().getEmail());
        assertThat(balance(own)).isEqualByComparingTo("1500");
    }

    @Test void replacementKeepsOldDeviceUntilConfirmationThenRevokesOutstandingProofs() {
        var f = fixture(); var e = enrolled(f.person()); var preview = preview(f);
        var transfer = challenges.authorize(p(f.person()), preview.previewId(), key()); prove(f.person(), transfer, e.secretBase64());
        var management = challenges.management(p(f.person()), OtpPurpose.REPLACE_DEVICE); prove(f.person(), management, e.secretBase64());
        assertThatThrownBy(() -> lifecycle.regenerate(p(f.person()), f.person().password(), management.id())).hasMessageContaining("binding conflict");
        var replacement = lifecycle.enroll(p(f.person()), f.person().password(), management.id(), null);
        assertThat(lifecycle.status(p(f.person())).deviceId()).isEqualTo(e.id());
        prove(f.person(), replacement.challenge(), replacement.secretBase64());
        lifecycle.confirm(p(f.person()), replacement.id(), replacement.challenge().id());
        assertThat(lifecycle.status(p(f.person())).deviceId()).isEqualTo(replacement.id());
        assertThat(challenges.read(p(f.person()), transfer.id()).state()).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("select secret_ciphertext from smart_otp_credentials where id=?", String.class, e.id())).isNull();
    }

    @Test void recoveryCodeIsOneTimeRevokesSessionsAndGrantOnlyPermitsEnrollment() throws Exception {
        var f = person(true); var e = lifecycle.enroll(p(f), f.password(), null, null); prove(f,e.challenge(),e.secretBase64());
        var codes = lifecycle.confirm(p(f), e.id(), e.challenge().id());
        String oldToken = jwt.generateToken(p(f));
        var grant = lifecycle.recover(p(f), f.password(), codes.recoveryCodes().getFirst());
        assertThat(send("GET", "/auth/smart-otp/status", oldToken, null).statusCode()).isEqualTo(401);
        var current = refreshed(f);
        assertThat(lifecycle.status(p(current)).enrollmentState()).isEqualTo("RECOVERY_REQUIRED");
        assertThatThrownBy(() -> lifecycle.enroll(p(current), current.password(), null, null)).hasMessageContaining("recovery required");
        assertThatThrownBy(() -> lifecycle.recover(p(current), current.password(), codes.recoveryCodes().getFirst())).hasMessageContaining("recovery invalid");
        assertThatThrownBy(() -> lifecycle.enroll(p(person(true)), current.password(), null, grant.enrollmentGrant())).hasMessageContaining("current password invalid");
        var replacement = lifecycle.enroll(p(current), current.password(), null, grant.enrollmentGrant());
        assertThatThrownBy(() -> lifecycle.enroll(p(current), current.password(), null, grant.enrollmentGrant())).hasMessageContaining("recovery required");
        prove(current, replacement.challenge(), replacement.secretBase64());
        lifecycle.confirm(p(current), replacement.id(), replacement.challenge().id());
        assertThat(lifecycle.status(p(current)).enrollmentState()).isEqualTo("ACTIVE");
    }

    @Test void revocationDoesNotReopenInitialEnrollmentAndRecoveryCodesCanBeRegenerated() {
        var f = person(true); var e = enrolled(f);
        var regenerate = challenges.management(p(f), OtpPurpose.REGENERATE_RECOVERY_CODES); prove(f,regenerate,e.secretBase64());
        var codes = lifecycle.regenerate(p(f), f.password(), regenerate.id());
        assertThat(codes.recoveryCodes()).hasSize(10);
        var revoke = challenges.management(p(f), OtpPurpose.REVOKE_DEVICE); prove(f,revoke,e.secretBase64());
        lifecycle.revoke(p(f), e.id(), f.password(), revoke.id());
        assertThat(lifecycle.status(p(f)).enrollmentState()).isEqualTo("RECOVERY_REQUIRED");
        assertThatThrownBy(() -> lifecycle.enroll(p(f), f.password(), null, null)).hasMessageContaining("recovery required");
        assertThat(lifecycle.recover(p(f), f.password(), codes.recoveryCodes().getFirst()).enrollmentGrant()).isNotBlank();
    }

    @Test void authVersionAndSuspensionInvalidateProofsWithoutDeletingDevice() {
        var f = fixture(); var e = enrolled(f.person()); var preview = preview(f); var key = key();
        var c = challenges.authorize(p(f.person()), preview.previewId(), key); prove(f.person(), c, e.secretBase64());
        new TransactionTemplate(manager).executeWithoutResult(s -> sessions.revokeAll(sessions.lockAuthenticatedUser(p(f.person())), RefreshSessionRevocationReason.LOGOUT_ALL, OffsetDateTime.now()));
        assertThatThrownBy(() -> transfers.execute(new TransferExecuteRequest(preview.previewId(),key,c.id()), f.person().user().getEmail())).hasMessageContaining("binding conflict");
        assertThat(lifecycle.status(p(refreshed(f.person()))).deviceId()).isEqualTo(e.id());
        jdbc.update("update users set is_active=false where id=?", f.person().user().getId());
        assertThatThrownBy(() -> lifecycle.enroll(p(refreshed(f.person())), f.person().password(), null,null)).isInstanceOf(RuntimeException.class);
    }

    @Test void expiredPendingSecretsAreErasedAndExpiredProofsCannotExecute() {
        var f = person(true); var e = lifecycle.enroll(p(f), f.password(), null,null);
        jdbc.update("update smart_otp_credentials set expires_at=now()-interval '1 minute' where id=?",e.id());
        cleanup.cleanup();
        assertThat(jdbc.queryForObject("select secret_ciphertext from smart_otp_credentials where id=?",String.class,e.id())).isNull();
        assertThatThrownBy(() -> prove(f,e.challenge(),e.secretBase64())).isInstanceOf(SmartOtpException.class);
    }

    @Test void realHttpFlowEnforcesAuthenticationOwnershipNoStoreAndExactOtpContract() throws Exception {
        var f = fixture(); String token=jwt.generateToken(p(f.person()));
        assertThat(send("GET","/auth/smart-otp/status",null,null).statusCode()).isEqualTo(401);
        var enrolled = send("POST","/auth/smart-otp/enrollments",token, Map.of("currentPassword",f.person().password()));
        assertThat(enrolled.statusCode()).isEqualTo(200);
        assertThat(enrolled.headers().firstValue("Cache-Control")).contains("no-store");
        var data=json.readTree(enrolled.body()).path("data");
        var e=json.treeToValue(data, Enrollment.class);
        var confirmed=send("POST","/auth/smart-otp/enrollments/"+e.id()+"/confirm",token,
                new Proof(e.challenge().id(),code(e.secretBase64(),e.challenge())));
        assertThat(confirmed.statusCode()).isEqualTo(200);
        var preview=preview(f); var key=key();
        var authorized=send("POST","/transfers/authorizations",token,new AuthorizationRequest(preview.previewId(),key));
        assertThat(authorized.statusCode()).isEqualTo(200);
        var c=json.treeToValue(json.readTree(authorized.body()).path("data"),ChallengeView.class);
        var foreign=jwt.generateToken(p(person(true)));
        assertThat(send("GET","/transfers/authorizations/"+c.id(),foreign,null).statusCode()).isEqualTo(404);
        assertThat(send("POST","/transfers/authorizations/"+c.id()+"/verify",token,new VerifyRequest(wrong(e.secretBase64(),c))).statusCode()).isEqualTo(400);
        assertThat(send("POST","/transfers/authorizations/"+c.id()+"/verify",token,new VerifyRequest(code(e.secretBase64(),c))).statusCode()).isEqualTo(200);
        var executed=send("POST","/transfers",token,new TransferExecuteRequest(preview.previewId(),key,c.id()));
        assertThat(executed.statusCode()).isEqualTo(200);
        assertEffect(f,UUID.fromString(json.readTree(executed.body()).path("data").path("id").asText()));
    }

    @Test void sharedRequestQuotaReturnsRetryAfter() throws Exception {
        var token=jwt.generateToken(p(person(true)));
        for(int i=0;i<20;i++) assertThat(send("GET","/auth/smart-otp/status",token,null).statusCode()).isEqualTo(200);
        var response=send("GET","/auth/smart-otp/status",token,null);
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
    }

    @Test void authorizedQrPaymentKeepsQrAndFinancialCommitAtomic() {
        var f=fixture(); var e=enrolled(f.person());
        var owner=users.findById(f.destination().getUser().getId()).orElseThrow();
        var q=qr.create(f.destination().getId(),new CreateQrRequest(QrType.PAYMENT_REQUEST,"1500","VND","Lunch"),key(),owner.getId());
        var preview=previews.create(new TransferPreviewRequest(null,f.source().getId(),null,null,null,null,null,q.id()),f.person().user().getEmail());
        var key=key(); var c=challenges.authorize(p(f.person()),preview.previewId(),key); prove(f.person(),c,e.secretBase64());
        var request=new TransferExecuteRequest(preview.previewId(),key,c.id());
        assertThatThrownBy(()->new TransactionTemplate(manager).execute(s->{transfers.execute(request,f.person().user().getEmail());throw new IllegalStateException("rollback");})).hasMessageContaining("rollback");
        assertThat(jdbc.queryForObject("select state from payment_qr_codes where id=?",String.class,q.id())).isEqualTo("ACTIVE");
        assertThat(challenges.read(p(f.person()),c.id()).state()).isEqualTo("VERIFIED");
        assertEffect(f,transfers.execute(request,f.person().user().getEmail()).id());
        assertThat(jdbc.queryForObject("select state from payment_qr_codes where id=?",String.class,q.id())).isEqualTo("PAID");
    }

    @Test void expiredTransferProofCannotExecuteAndNewPreviewDoesNotReuseOldProof() {
        var f=fixture(); var e=enrolled(f.person()); var preview=preview(f); var key=key();
        var c=challenges.authorize(p(f.person()),preview.previewId(),key); prove(f.person(),c,e.secretBase64());
        jdbc.update("update smart_otp_challenges set created_at=now()-interval '3 minutes', expires_at=now()-interval '1 minute' where id=?",c.id());
        assertThatThrownBy(()->transfers.execute(new TransferExecuteRequest(preview.previewId(),key,c.id()),f.person().user().getEmail())).hasMessageContaining("expired");
        assertThat(challenges.authorize(p(f.person()),preview.previewId(),key).state()).isEqualTo("EXPIRED");
        assertThat(balance(f.source())).isEqualByComparingTo("5000");
        assertThatThrownBy(()->transfers.execute(new TransferExecuteRequest(preview(f).previewId(),key,c.id()),f.person().user().getEmail())).isInstanceOf(SmartOtpException.class);
    }

    @Test void recoveryAndExecuteRaceCannotLeaveAnUnauthorizedMoneyMovement() throws Exception {
        var f=fixture(); var e=lifecycle.enroll(p(f.person()),f.person().password(),null,null); prove(f.person(),e.challenge(),e.secretBase64());
        var codes=lifecycle.confirm(p(f.person()),e.id(),e.challenge().id());
        var preview=preview(f); var key=key(); var c=challenges.authorize(p(f.person()),preview.previewId(),key); prove(f.person(),c,e.secretBase64());
        var results=race(()->transfers.execute(new TransferExecuteRequest(preview.previewId(),key,c.id()),f.person().user().getEmail()),
                ()->lifecycle.recover(p(f.person()),f.person().password(),codes.recoveryCodes().getFirst()));
        assertThat(results.get(1)).isInstanceOf(RecoveryGrant.class);
        if(results.getFirst() instanceof TransactionResponse tx) assertEffect(f,tx.id());
        else {
            assertThat(results.getFirst()).isInstanceOf(SmartOtpException.class);
            assertThat(balance(f.source())).isEqualByComparingTo("5000");
            assertThat(balance(f.destination())).isEqualByComparingTo("0");
        }
        assertThat(lifecycle.status(p(refreshed(f.person()))).enrollmentState()).isEqualTo("RECOVERY_REQUIRED");
    }

    @Test void concurrentRecoveryConsumesOneCodeAndOnlyOneGrant() throws Exception {
        var f=person(true); var e=lifecycle.enroll(p(f),f.password(),null,null); prove(f,e.challenge(),e.secretBase64());
        var codes=lifecycle.confirm(p(f),e.id(),e.challenge().id());
        var results=race(()->lifecycle.recover(p(f),f.password(),codes.recoveryCodes().getFirst()),
                ()->lifecycle.recover(p(f),f.password(),codes.recoveryCodes().getFirst()));
        assertThat(results.stream().filter(RecoveryGrant.class::isInstance)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from smart_otp_recovery_codes where user_id=? and consumed_at is not null",Integer.class,f.user().getId())).isEqualTo(1);
    }

    @Test void rolloutModesKeepLegacyBehaviorUntilEnforcementAndFailClosedForOtpWhenDisabled() {
        try {
            otpProperties.setMode(SmartOtpProperties.Mode.ENROLLMENT_ONLY);
            var f=fixture(); var preview=preview(f);
            assertThat(preview.authorizationRequirement()).isEqualTo("NONE");
            transfers.execute(new TransferExecuteRequest(preview.previewId(),key()),f.person().user().getEmail());
            otpProperties.setMode(SmartOtpProperties.Mode.DISABLED);
            assertThat(lifecycle.status(p(f.person())).mode()).isEqualTo("DISABLED");
            assertThatThrownBy(()->lifecycle.enroll(p(f.person()),f.person().password(),null,null)).hasMessageContaining("unavailable");
        } finally { otpProperties.setMode(SmartOtpProperties.Mode.ENFORCED); }
    }

    private record Person(User user,String password) {}
    private record Fixture(Person person,Account source,Account destination) {}
    private Person person(boolean verified) {
        String password=key();
        return new Person(users.saveAndFlush(User.builder().email(key()+"@otp.test").fullName("OTP User")
                .passwordHash(passwords.encode(password)).emailVerifiedAt(verified?OffsetDateTime.now():null).build()),password);
    }
    private Person refreshed(Person f) { return new Person(users.findById(f.user().getId()).orElseThrow(),f.password()); }
    private AuthenticatedUserPrincipal p(Person f) { return AuthenticatedUserPrincipal.from(f.user()); }
    private Fixture fixture() {var payer=person(true); return new Fixture(payer,account(payer.user(),"5000"),account(person(true).user(),"0"));}
    private Account account(User user,String balance) {return accounts.saveAndFlush(Account.builder().user(user).accountNumber(Long.toString(NUMBERS.incrementAndGet()))
            .accountType(AccountType.PERSONAL).status(AccountStatus.ACTIVE).currency("VND").balance(new BigDecimal(balance)).build());}
    private TransferPreviewResponse preview(Fixture f) {return previews.create(new TransferPreviewRequest(TransferPreviewMode.EXTERNAL,f.source().getId(),null,
            f.destination().getAccountNumber(),"1500","VND","Lunch"),f.person().user().getEmail());}
    private Enrollment enrolled(Person f) {var e=lifecycle.enroll(p(f),f.password(),null,null);prove(f,e.challenge(),e.secretBase64());lifecycle.confirm(p(f),e.id(),e.challenge().id());return e;}
    private void prove(Person f,ChallengeView c,String secret) {verification.verify(p(f),c.id(),c.purpose(),code(secret,c)).requireSuccess();}
    private String code(String secret,ChallengeView c) {return Ocra.generate(Base64.getDecoder().decode(secret),Base64.getDecoder().decode(c.payloadBase64()));}
    private String wrong(String secret,ChallengeView c) {return code(secret,c).equals("00000000")?"00000001":"00000000";}
    private String key() {return UUID.randomUUID().toString();}
    private BigDecimal balance(Account a) {return accounts.findById(a.getId()).orElseThrow().getBalance();}
    private void assertEffect(Fixture f,UUID tx) {
        assertThat(balance(f.source())).isEqualByComparingTo("3500");assertThat(balance(f.destination())).isEqualByComparingTo("1500");
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where transaction_id=?",Integer.class,tx)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select sum(case when direction='DEBIT' then amount else -amount end) from ledger_entries where transaction_id=?",BigDecimal.class,tx)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=? and event_type='TransferCompleted'",Integer.class,tx)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where transaction_id=?",Integer.class,tx)).isEqualTo(2);
    }
    private HttpResponse<String> send(String method,String path,String token,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private List<Object> race(Callable<?> a,Callable<?> b) throws Exception {
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            List<Future<Object>> results=new ArrayList<>();
            for(var task:List.of(a,b))results.add(executor.submit(()->{start.await();try{return task.call();}catch(Exception ex){return ex;}}));
            start.countDown();return List.of(results.get(0).get(30,TimeUnit.SECONDS),results.get(1).get(30,TimeUnit.SECONDS));
        }
    }
    private static String randomKey() {byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getEncoder().encodeToString(bytes);}
}
