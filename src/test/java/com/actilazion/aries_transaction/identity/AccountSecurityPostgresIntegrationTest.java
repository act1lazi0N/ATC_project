package com.actilazion.aries_transaction.identity;

import com.actilazion.aries_transaction.audit.application.IdentityAuditService;
import com.actilazion.aries_transaction.audit.domain.IdentityAuditEventType;
import com.actilazion.aries_transaction.config.JwtConfig;
import com.actilazion.aries_transaction.config.JwtService;
import com.actilazion.aries_transaction.identity.application.*;
import com.actilazion.aries_transaction.identity.domain.*;
import com.actilazion.aries_transaction.identity.domain.exception.AccountSecurityException;
import com.actilazion.aries_transaction.identity.dto.*;
import com.actilazion.aries_transaction.identity.infrastructure.*;
import com.actilazion.aries_transaction.notification.application.*;
import com.actilazion.aries_transaction.notification.domain.*;
import com.actilazion.aries_transaction.notification.infrastructure.EmailDeliveryRepository;
import com.actilazion.aries_transaction.support.PostgresIntegrationTestSupport;
import com.actilazion.aries_transaction.support.TestSecrets;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@ActiveProfiles("test")
class AccountSecurityPostgresIntegrationTest extends PostgresIntegrationTestSupport {
    private static final String KEY = TestSecrets.newBase64Key();
    private static final String OLD_PASSWORD = "current-password-123";
    private static final String NEW_PASSWORD = "replacement-password-456";
    private static final AtomicInteger IP = new AtomicInteger();

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add("security.account-security.enabled", () -> true);
        registry.add("security.account-security.reset-signing-key", () -> KEY);
        registry.add("security.account-security.reset-public-url", () -> "http://localhost:3000/reset-password");
    }

    @Autowired WebApplicationContext context;
    @Autowired AuthService auth;
    @Autowired PasswordService service;
    @Autowired UserRepository users;
    @Autowired RefreshSessionRepository sessions;
    @Autowired PasswordResetChallengeRepository challenges;
    @Autowired PasswordResetTokenService tokens;
    @Autowired EmailVerificationService verification;
    @Autowired EmailVerificationTokenService verificationTokens;
    @Autowired EmailVerificationChallengeRepository verificationChallenges;
    @Autowired EmailDeliveryRepository deliveries;
    @Autowired EmailDeliveryService emailService;
    @Autowired PasswordResetCleanupService cleanup;
    @Autowired JwtService jwt;
    @Autowired JwtConfig jwtConfig;
    @Autowired AccountSecurityProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    private MockMvc mvc;
    private String ip;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        ip = "192.0.2." + IP.incrementAndGet();
    }

    @Test
    void changePasswordRevokesAllTokensAndQueuesOnlySafeSecurityEmail() throws Exception {
        AuthResponse first = register();
        AuthResponse second = auth.login(new LoginRequest(first.user().email(), OLD_PASSWORD));
        AuthResponse unrelated = register();
        service.requestReset(first.user().email(), ip);
        String resetToken = resetToken(first);

        mvc.perform(postJson("change-password", """
                {"currentPassword":"%s","newPassword":"%s"}
                """.formatted(OLD_PASSWORD, NEW_PASSWORD)).header("Authorization", "Bearer " + first.accessToken()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());

        assertRevoked(first);
        assertRevoked(second);
        assertThatThrownBy(() -> service.resetPassword(resetToken, "another-password-789", ip))
                .isInstanceOf(AccountSecurityException.class);
        assertThatThrownBy(() -> auth.login(new LoginRequest(first.user().email(), OLD_PASSWORD))).isInstanceOf(RuntimeException.class);
        assertThat(auth.login(new LoginRequest(first.user().email(), NEW_PASSWORD)).accessToken()).isNotBlank();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + unrelated.accessToken()))
                .andExpect(status().isOk());
        assertThat(users.findById(first.user().id()).orElseThrow().getEmailVerifiedAt()).isNull();
        var notice = securityNotice(first.user().id());
        var work = emailService.claim(notice).orElseThrow();
        assertThat(work.message().textBody()).contains("All existing sessions were revoked")
                .doesNotContain(OLD_PASSWORD, NEW_PASSWORD, resetToken);
        emailService.markDelivered(work, 1);
        assertThat(deliveries.findById(notice).orElseThrow().getStatus()).isEqualTo(EmailDeliveryStatus.DELIVERED);
    }

    @Test
    void passwordFailuresDoNotChangeCredentialsOrConsumeResetToken() throws Exception {
        AuthResponse account = register();
        for (String[] input : List.of(new String[]{"wrong-password", NEW_PASSWORD, "CURRENT_PASSWORD_INVALID"},
                new String[]{OLD_PASSWORD, OLD_PASSWORD, "PASSWORD_UNCHANGED"},
                new String[]{OLD_PASSWORD, "é".repeat(37), "VALIDATION_ERROR"})) {
            mvc.perform(postJson("change-password", "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(input[0], input[1]))
                            .header("Authorization", "Bearer " + account.accessToken()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(input[2]));
        }
        service.requestReset(account.user().email(), ip);
        String token = resetToken(account);
        assertThatThrownBy(() -> service.resetPassword(token, OLD_PASSWORD, ip)).hasMessageContaining("differ");
        assertThat(challenge(account).getConsumedAt()).isNull();
        assertThat(users.findById(account.user().id()).orElseThrow().getAuthVersion()).isZero();
        service.resetPassword(token, NEW_PASSWORD, ip);
    }

    @Test
    void forgotResponseAndCooldownDoNotExposeIdentityOrSuspension() throws Exception {
        AuthResponse active = register();
        AuthResponse suspended = register();
        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", suspended.user().id());
        for (String email : List.of(active.user().email(), suspended.user().email(), "missing-" + UUID.randomUUID() + "@test.local")) {
            for (int attempt = 0; attempt < 3; attempt++) {
                String response = mvc.perform(postJson("forgot-password", "{\"email\":\"" + email + "\"}"))
                        .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.message").value("If an eligible account exists, password reset instructions will be emailed."))
                        .andExpect(jsonPath("$.data").doesNotExist())
                        .andReturn().getResponse().getContentAsString();
                assertThat(response).doesNotContain(email);
            }
            mvc.perform(postJson("forgot-password", "{\"email\":\"" + email + "\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                    .andExpect(header().string("Access-Control-Expose-Headers",
                            org.hamcrest.Matchers.containsString("Retry-After")))
                    .andExpect(result -> assertThat(Integer.parseInt(result.getResponse().getHeader("Retry-After")))
                            .isPositive());
        }
        assertThat(challenges.findAllByUser_IdOrderByCreatedAtDesc(active.user().id())).hasSize(1);
        assertThat(challenges.findAllByUser_IdOrderByCreatedAtDesc(suspended.user().id())).isEmpty();
        assertThat(users.findById(active.user().id()).orElseThrow().getFailedLoginAttempts()).isZero();
    }

    @Test
    void resetWorksForUnverifiedAndTemporarilyLockedUserWithoutAuthenticatingThem() throws Exception {
        AuthResponse account = register();
        service.requestReset(account.user().email(), ip);
        String token = resetToken(account);
        jdbc.update("UPDATE users SET locked_until = ?, failed_login_attempts = 3 WHERE id = ?",
                OffsetDateTime.now().plusMinutes(5), account.user().id());
        mvc.perform(postJson("reset-password", "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, NEW_PASSWORD)))
                .andExpect(status().isOk()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
        User user = users.findById(account.user().id()).orElseThrow();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getEmailVerifiedAt()).isNull();
        assertRevoked(account);
        assertThat(auth.login(new LoginRequest(user.getEmail(), NEW_PASSWORD)).accessToken()).isNotBlank();
        mvc.perform(postJson("reset-password", "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, NEW_PASSWORD)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_RESET_TOKEN_INVALID"));
    }

    @Test
    void expiredReplacedSuspendedAndVerificationTokensCannotResetPassword() {
        AuthResponse account = register();
        service.requestReset(account.user().email(), ip);
        String older = resetToken(account);
        service.requestReset(account.user().email(), ip);
        String latest = resetToken(account);
        invalidReset(older);
        char replacement = latest.charAt(37) == 'A' ? 'B' : 'A';
        invalidReset(latest.substring(0, 37) + replacement + latest.substring(38));
        verification.request(account.user().id(), ip);
        invalidReset(verificationTokens.tokenFor(verificationChallenges
                .findAllByUser_IdOrderByCreatedAtDesc(account.user().id()).getFirst()));
        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", account.user().id());
        invalidReset(latest);
        assertThat(users.findById(account.user().id()).orElseThrow().getIsActive()).isFalse();
        jdbc.update("UPDATE users SET is_active = true WHERE id = ?", account.user().id());
        jdbc.update("UPDATE password_reset_challenges SET created_at = ?, expires_at = ? WHERE user_id = ?",
                OffsetDateTime.now().minusHours(2), OffsetDateTime.now().minusHours(1), account.user().id());
        invalidReset(resetToken(account));
        assertThat(users.findById(account.user().id()).orElseThrow().getAuthVersion()).isZero();
    }

    @Test
    void logoutAllRequiresBearerAndOriginAndDoesNotRevokeNextLogin() throws Exception {
        AuthResponse account = register();
        AuthResponse otherDevice = auth.login(new LoginRequest(account.user().email(), OLD_PASSWORD));
        var principal = principal(account);
        mvc.perform(postJson("logout-all", "{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + account.accessToken()))
                .andExpect(status().isForbidden());
        mvc.perform(postJson("logout-all", "{}").header("Authorization", "Bearer " + account.accessToken()))
                .andExpect(status().isOk());
        assertRevoked(account);
        assertRevoked(otherDevice);
        AuthResponse fresh = auth.login(new LoginRequest(account.user().email(), OLD_PASSWORD));
        assertThatThrownBy(() -> service.logoutAll(principal, ip)).hasMessage("Unauthorized");
        assertThat(auth.refresh(fresh.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void legacyJwtIsAcceptedOnlyAtVersionZeroAndMalformedVersionIsRejected() throws Exception {
        AuthResponse account = register();
        String legacy = signed(account.user().id(), null);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + legacy)).andExpect(status().isOk());
        for (Object version : List.of(-1, 0.5, "0", true)) {
            mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + signed(account.user().id(), version)))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        }
        service.logoutAll(principal(account), ip);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + legacy)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"users", "refresh_sessions", "identity_audit_logs", "email_deliveries"})
    void persistenceFailureRollsBackPasswordChallengeSessionsAuditAndEmailTogether(String table) {
        AuthResponse account = register();
        service.requestReset(account.user().email(), ip);
        String token = resetToken(account);
        String before = users.findById(account.user().id()).orElseThrow().getPasswordHash();
        long deliveryCount = deliveries.count();
        jdbc.execute("CREATE FUNCTION fail_account_security_write() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'injected account security persistence failure'; END $$");
        String operation = table.equals("users") || table.equals("refresh_sessions") ? "UPDATE" : "INSERT";
        jdbc.execute("CREATE TRIGGER fail_account_security_write BEFORE " + operation + " ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION fail_account_security_write()");
        try {
            assertThatThrownBy(() -> service.resetPassword(token, NEW_PASSWORD, ip))
                    .hasStackTraceContaining("injected account security persistence failure");
        } finally {
            jdbc.execute("DROP TRIGGER fail_account_security_write ON " + table);
            jdbc.execute("DROP FUNCTION fail_account_security_write()");
        }
        User user = users.findById(account.user().id()).orElseThrow();
        assertThat(user.getPasswordHash()).isEqualTo(before);
        assertThat(user.getAuthVersion()).isZero();
        assertThat(challenge(account).getConsumedAt()).isNull();
        assertThat(deliveries.count()).isEqualTo(deliveryCount);
        assertThat(sessions.findAllByUserId(user.getId())).allMatch(s -> s.getRevokedAt() == null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_audit_logs WHERE user_id = ? AND event_type = 'PASSWORD_RESET_COMPLETED'",
                Long.class, user.getId())).isZero();
    }

    @Test
    void concurrentResetConsumesTokenExactlyOnce() throws Exception {
        AuthResponse account = register();
        service.requestReset(account.user().email(), ip);
        String token = resetToken(account);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) results.add(pool.submit(() -> {
                start.await();
                try { service.resetPassword(token, NEW_PASSWORD, ip); return true; }
                catch (AccountSecurityException ex) { return false; }
            }));
            start.countDown();
            assertThat(List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(users.findById(account.user().id()).orElseThrow().getAuthVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_audit_logs WHERE user_id = ? AND event_type = 'PASSWORD_RESET_COMPLETED'",
                Long.class, account.user().id())).isEqualTo(1);
    }

    @Test
    void refreshWaitingOnRevocationCannotCreateAnotherSession() throws Exception {
        AuthResponse account = register();
        var caller = principal(account);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch refreshing = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> revoked = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                users.findByIdWithLock(account.user().id()).orElseThrow();
                locked.countDown();
                await(release);
                service.logoutAll(caller, ip);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> refreshed = pool.submit(() -> {
                refreshing.countDown();
                assertThatThrownBy(() -> auth.refresh(account.refreshToken())).hasMessage("Unauthorized");
            });
            assertThat(refreshing.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait();
            release.countDown();
            revoked.get(20, TimeUnit.SECONDS);
            refreshed.get(20, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(sessions.countByUserId(account.user().id())).isEqualTo(1);
    }

    @Test
    void loginWaitingOnPasswordChangeCannotAuthenticateWithOldPassword() throws Exception {
        AuthResponse account = register();
        var caller = principal(account);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> changed = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                users.findByIdWithLock(account.user().id()).orElseThrow();
                locked.countDown();
                await(release);
                service.changePassword(caller, OLD_PASSWORD, NEW_PASSWORD, ip);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> login = pool.submit(() -> assertThatThrownBy(() -> auth.login(
                    new LoginRequest(account.user().email(), OLD_PASSWORD))).isInstanceOf(RuntimeException.class));
            awaitDatabaseLockWait();
            release.countDown();
            changed.get(20, TimeUnit.SECONDS);
            login.get(20, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(sessions.countByUserId(account.user().id())).isEqualTo(1);
        assertThat(auth.login(new LoginRequest(account.user().email(), NEW_PASSWORD)).accessToken()).isNotBlank();
    }

    @Test
    void revocationWaitingOnRefreshInvalidatesTheReplacementToo() throws Exception {
        AuthResponse account = register();
        var caller = principal(account);
        CountDownLatch rotated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<AuthResponse> refreshed = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                AuthResponse replacement = auth.refresh(account.refreshToken());
                rotated.countDown();
                await(release);
                return replacement;
            }));
            assertThat(rotated.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> revoked = pool.submit(() -> service.logoutAll(caller, ip));
            awaitDatabaseLockWait();
            release.countDown();
            AuthResponse replacement = refreshed.get(20, TimeUnit.SECONDS);
            revoked.get(20, TimeUnit.SECONDS);
            assertRevoked(replacement);
        } finally { release.countDown(); }
    }

    private void awaitDatabaseLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        fail("Expected a competing PostgreSQL transaction to wait for the user lock");
    }

    @Test
    void invalidatedEmailsAreCancelledAndExpiredDeadLettersCannotBeRedriven() {
        AuthResponse account = register();
        service.requestReset(account.user().email(), ip);
        UUID firstId = resetDelivery(challenge(account).getId());
        service.requestReset(account.user().email(), ip);
        assertThat(emailService.claim(firstId)).isEmpty();
        assertThat(deliveries.findById(firstId).orElseThrow().getStatus()).isEqualTo(EmailDeliveryStatus.CANCELLED);
        UUID currentId = resetDelivery(challenge(account).getId());
        var work = emailService.claim(currentId).orElseThrow();
        assertThat(work.message().textBody()).contains("http://localhost:3000/reset-password?token=");
        emailService.markFailed(work, false, "SMTP_REJECTED", 1);
        jdbc.update("UPDATE password_reset_challenges SET created_at = ?, expires_at = ? WHERE user_id = ?",
                OffsetDateTime.now().minusHours(2), OffsetDateTime.now().minusHours(1), account.user().id());
        User admin = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.local").fullName("Staff")
                .passwordHash("unused").role(Role.ADMIN).build());
        assertThatThrownBy(() -> emailService.redrive(admin.getId(), currentId, ip)).isInstanceOf(RuntimeException.class);
        cleanup.purgeExpired();
        assertThat(deliveries.findById(currentId).orElseThrow().getStatus()).isEqualTo(EmailDeliveryStatus.CANCELLED);
    }

    @Test
    void featureFlagStopsNewOperationsWithoutChangingExistingSessions() throws Exception {
        AuthResponse account = register();
        properties.setEnabled(false);
        try {
            mvc.perform(postJson("forgot-password", "{\"email\":\"" + account.user().email() + "\"}"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("FEATURE_DISABLED"));
            mvc.perform(postJson("logout-all", "{}").header("Authorization", "Bearer " + account.accessToken()))
                    .andExpect(status().isServiceUnavailable());
            assertThat(auth.refresh(account.refreshToken()).accessToken()).isNotBlank();
        } finally { properties.setEnabled(true); }
    }

    @Test
    void securityRetentionDeletesOnlyResolvedEmailsAndKeepsUnresolvedWork() {
        Map<EmailDeliveryStatus, UUID> fixture = new EnumMap<>(EmailDeliveryStatus.class);
        Map<EmailDeliveryStatus, UUID> challengeIds = new EnumMap<>(EmailDeliveryStatus.class);
        for (EmailDeliveryStatus status : EmailDeliveryStatus.values()) {
            AuthResponse account = register();
            service.requestReset(account.user().email(), ip);
            UUID challengeId = challenge(account).getId();
            UUID deliveryId = resetDelivery(challengeId);
            fixture.put(status, deliveryId);
            challengeIds.put(status, challengeId);
            jdbc.update("UPDATE email_deliveries SET status = ? WHERE id = ?", status.name(), deliveryId);
            if (status != EmailDeliveryStatus.DELIVERED && status != EmailDeliveryStatus.CANCELLED) {
                jdbc.update("UPDATE password_reset_challenges SET expires_at = ? WHERE id = ?",
                        OffsetDateTime.now().plusDays(90), challengeId);
            }
        }
        var futureClock = java.time.Clock.fixed(OffsetDateTime.now().plusDays(60).toInstant(), java.time.ZoneOffset.UTC);
        var futureCleanup = new PasswordResetCleanupService(challenges, deliveries, properties, futureClock);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> futureCleanup.purgeExpired());
        for (var entry : fixture.entrySet()) {
            boolean retained = entry.getKey() != EmailDeliveryStatus.DELIVERED && entry.getKey() != EmailDeliveryStatus.CANCELLED;
            assertThat(deliveries.existsById(entry.getValue())).isEqualTo(retained);
            assertThat(challenges.existsById(challengeIds.get(entry.getKey()))).isEqualTo(retained);
        }
    }

    private AuthResponse register() {
        return auth.register(new RegisterRequest("Account Security", UUID.randomUUID() + "@test.local", OLD_PASSWORD));
    }

    private AuthenticatedUserPrincipal principal(AuthResponse account) {
        return AuthenticatedUserPrincipal.from(users.findById(account.user().id()).orElseThrow());
    }

    private PasswordResetChallenge challenge(AuthResponse account) {
        return challenges.findAllByUser_IdOrderByCreatedAtDesc(account.user().id()).getFirst();
    }

    private String resetToken(AuthResponse account) { return tokens.tokenFor(challenge(account)); }

    private UUID resetDelivery(UUID challengeId) {
        return jdbc.queryForObject("SELECT id FROM email_deliveries WHERE password_reset_challenge_id = ?", UUID.class, challengeId);
    }

    private UUID securityNotice(UUID userId) {
        return jdbc.queryForObject("SELECT d.id FROM email_deliveries d JOIN identity_audit_logs a ON a.id = d.security_audit_event_id WHERE a.user_id = ?",
                UUID.class, userId);
    }

    private void invalidReset(String token) {
        assertThatThrownBy(() -> service.resetPassword(token, NEW_PASSWORD, ip))
                .isInstanceOf(AccountSecurityException.class).hasMessage("Password reset token is invalid or expired");
    }

    private void assertRevoked(AuthResponse session) throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        assertThatThrownBy(() -> auth.refresh(session.refreshToken())).hasMessage("Unauthorized");
    }

    private MockHttpServletRequestBuilder postJson(String endpoint, String body) {
        return post("/api/v1/auth/" + endpoint).header("Origin", "http://localhost:3000")
                .contentType(MediaType.APPLICATION_JSON).content(body).with(request -> { request.setRemoteAddr(ip); return request; });
    }

    private String signed(UUID userId, Object version) {
        var builder = Jwts.builder().subject(userId.toString()).issuer(jwtConfig.getIssuer())
                .audience().add(jwtConfig.getAudience()).and().claim("typ", "access")
                .id(UUID.randomUUID().toString()).issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60000));
        if (version != null) builder.claim("authVersion", version);
        return builder.signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtConfig.getSecret())), Jwts.SIG.HS256).compact();
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Latch timed out"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }
}
