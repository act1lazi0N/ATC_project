package com.actilazion.aries_transaction.payment;

import com.actilazion.aries_transaction.account.domain.*;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.audit.infrastructure.AuditLogRepository;
import com.actilazion.aries_transaction.config.JwtService;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.identity.domain.*;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.ledger.domain.LedgerDirection;
import com.actilazion.aries_transaction.ledger.infrastructure.LedgerEntryRepository;
import com.actilazion.aries_transaction.payment.application.PaymentQrService;
import com.actilazion.aries_transaction.payment.domain.*;
import com.actilazion.aries_transaction.payment.dto.*;
import com.actilazion.aries_transaction.payment.infrastructure.PaymentQrRepository;
import com.actilazion.aries_transaction.transaction.application.*;
import com.actilazion.aries_transaction.transaction.domain.exception.*;
import com.actilazion.aries_transaction.transaction.dto.*;
import com.actilazion.aries_transaction.transaction.infrastructure.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class QrPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }
    private static final AtomicLong NUMBERS = new AtomicLong(860_000_000_000L);
    @Autowired PaymentQrService qrService;
    @Autowired PaymentQrRepository qrRepository;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired TransferPreviewService previews;
    @Autowired TransferService transfers;
    @Autowired TransferPreviewRepository previewRepository;
    @Autowired TransactionRepository transactions;
    @Autowired LedgerEntryRepository ledger;
    @Autowired AuditLogRepository audit;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JwtService jwt;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void creationIsIdempotentOwnerScopedAndAllowsOnlyOneActiveAccountCode() throws Exception {
        var owner = user(); var account = account(owner, "0"); var key = key();
        var request = new CreateQrRequest(QrType.ACCOUNT, null, "VND", null);
        var results = race(() -> qrService.create(account.getId(), request, key, owner.getId()),
                () -> qrService.create(account.getId(), request, key, owner.getId()));
        assertThat(results).allMatch(QrResponse.class::isInstance);
        assertThat(((QrResponse) results.get(0)).id()).isEqualTo(((QrResponse) results.get(1)).id());
        assertThatThrownBy(() -> qrService.create(account.getId(), fixedRequest(), key, owner.getId()))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> qrService.create(account.getId(), request, key(), owner.getId()))
                .isInstanceOfSatisfying(QrException.class, ex -> assertThat(ex.getCode()).isEqualTo("QR_ACCOUNT_EXISTS"));
        var id = ((QrResponse) results.get(0)).id();
        assertThat(qrService.revoke(id, owner.getId()).state()).isEqualTo("REVOKED");
        assertThat(qrService.revoke(id, owner.getId()).state()).isEqualTo("REVOKED");
        assertThat(qrService.create(account.getId(), request, key(), owner.getId()).id()).isNotEqualTo(id);
    }

    @Test
    void distinctPayersRaceForOnePaymentOnlyAndRetrySurvivesExpiryAndCleanup() throws Exception {
        var f = fixture(); var other = user(); var otherSource = account(other, "5000");
        var p1 = preview(f.payer(), f.source(), f.qr()); var p2 = preview(other, otherSource, f.qr());
        var r1 = new TransferExecuteRequest(p1, key()); var r2 = new TransferExecuteRequest(p2, key());
        var outcomes = race(() -> transfers.execute(r1, f.payer().getEmail()), () -> transfers.execute(r2, other.getEmail()));
        assertThat(outcomes.stream().filter(TransactionResponse.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(QrException.class::isInstance)).hasSize(1);
        int winner = outcomes.get(0) instanceof TransactionResponse ? 0 : 1;
        var response = (TransactionResponse) outcomes.get(winner);
        assertFinancialEffect(response.id(), f.destination().getId());
        assertThat(balance(f.source()).add(balance(otherSource))).isEqualByComparingTo("8500");
        assertThat(qrRepository.findById(f.qr().id()).orElseThrow().getTransactionId()).isEqualTo(response.id());
        jdbc.update("update transfer_previews set expires_at = now() - interval '3 days' where id in (?, ?)", p1, p2);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                previewRepository.deleteExpiredBefore(OffsetDateTime.now().minusDays(1)));
        var winningRequest = winner == 0 ? r1 : r2;
        var winningUser = winner == 0 ? f.payer() : other;
        assertThat(transfers.execute(winningRequest, winningUser.getEmail())).isEqualTo(response);
        assertThatThrownBy(() -> transfers.execute(new TransferExecuteRequest(winningRequest.previewId(), key()), winningUser.getEmail()))
                .isInstanceOf(TransferPreviewUnavailableException.class);
    }

    @Test
    void reusableAccountQrSupportsMultiplePaymentsButEachPreviewExecutesOnce() {
        var owner = user(); var destination = account(owner, "0"); var payer = user(); var source = account(payer, "5000");
        var qr = qrService.create(destination.getId(), new CreateQrRequest(QrType.ACCOUNT, null, "VND", null), key(), owner.getId());
        for (int i = 0; i < 2; i++) {
            var p = previews.create(new TransferPreviewRequest(null, source.getId(), null, null, "1000", null, "Lunch", qr.id()), payer.getEmail());
            var request = new TransferExecuteRequest(p.previewId(), key());
            var first = transfers.execute(request, payer.getEmail());
            assertThat(transfers.execute(request, payer.getEmail())).isEqualTo(first);
        }
        assertThat(balance(source)).isEqualByComparingTo("3000");
        assertThat(balance(destination)).isEqualByComparingTo("2000");
        assertThat(qrRepository.findById(qr.id()).orElseThrow().getState()).isEqualTo(QrState.ACTIVE);
    }

    @Test
    void sameKeyWithAnotherPreviewConflictsAndReversalDoesNotReopenPaymentRequest() {
        var f = fixture();
        var p1 = preview(f.payer(), f.source(), f.qr()); var p2 = preview(f.payer(), f.source(), f.qr());
        var key = key();
        var first = transfers.execute(new TransferExecuteRequest(p1, key), f.payer().getEmail());
        assertThatThrownBy(() -> transfers.execute(new TransferExecuteRequest(p2, key), f.payer().getEmail()))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> transfers.execute(new TransferExecuteRequest(p2, key()), f.payer().getEmail()))
                .isInstanceOfSatisfying(QrException.class, ex -> assertThat(ex.getCode()).isEqualTo("QR_PAID"));
        var operator = user(); operator.setRole(Role.OPERATOR); users.saveAndFlush(operator);
        transfers.reverse(first.id(), new ReversalRequest(key(), "QR reversal test"), operator.getEmail());
        assertThat(qrRepository.findById(f.qr().id()).orElseThrow().getState()).isEqualTo(QrState.PAID);
        assertThatThrownBy(() -> preview(f.payer(), f.source(), f.qr())).isInstanceOf(QrException.class);
        assertThat(transfers.execute(new TransferExecuteRequest(p1, key), f.payer().getEmail())).isEqualTo(first);
    }

    @Test
    void qrPreviewDerivesOwnAccountModeAndCapsLifetimeAtQrDeadline() {
        var owner = user(); var source = account(owner, "5000"); var destination = account(owner, "0");
        var qr = qrService.create(destination.getId(), fixedRequest(), key(), owner.getId());
        jdbc.update("update payment_qr_codes set expires_at = now() + interval '1 minute' where id = ?", qr.id());
        var id = preview(owner, source, qr);
        var preview = previewRepository.findById(id).orElseThrow();
        assertThat(preview.getMode()).isEqualTo(com.actilazion.aries_transaction.transaction.domain.TransferPreviewMode.OWN_ACCOUNTS);
        assertThat(preview.getExpiresAt().toInstant()).isEqualTo(qrRepository.findById(qr.id()).orElseThrow().getExpiresAt().toInstant());
        assertThatThrownBy(() -> previews.create(new TransferPreviewRequest(
                com.actilazion.aries_transaction.transaction.domain.TransferPreviewMode.EXTERNAL,
                source.getId(), null, null, null, null, null, qr.id()), owner.getEmail())).isInstanceOf(IllegalArgumentException.class);
        transfers.execute(new TransferExecuteRequest(id, key()), owner.getEmail());
        assertThat(balance(source)).isEqualByComparingTo("3500");
        assertThat(balance(destination)).isEqualByComparingTo("1500");
    }

    @Test
    void oppositeQrPaymentsCompleteWithoutDeadlockOrLostBalances() throws Exception {
        var a = user(); var b = user(); var aa = account(a, "5000"); var ba = account(b, "5000");
        var aq = qrService.create(aa.getId(), fixedRequest(), key(), a.getId());
        var bq = qrService.create(ba.getId(), fixedRequest(), key(), b.getId());
        var ap = preview(a, aa, bq); var bp = preview(b, ba, aq);
        var outcomes = race(() -> transfers.execute(new TransferExecuteRequest(ap, key()), a.getEmail()),
                () -> transfers.execute(new TransferExecuteRequest(bp, key()), b.getEmail()));
        assertThat(outcomes).allMatch(TransactionResponse.class::isInstance);
        assertThat(balance(aa)).isEqualByComparingTo("5000");
        assertThat(balance(ba)).isEqualByComparingTo("5000");
        assertThat(qrRepository.findById(aq.id()).orElseThrow().getState()).isEqualTo(QrState.PAID);
        assertThat(qrRepository.findById(bq.id()).orElseThrow().getState()).isEqualTo(QrState.PAID);
    }

    @Test
    void rollbackAfterFinancialWritesRestoresQrPreviewBalancesLedgerAuditAndOutbox() {
        var f = fixture(); var p = preview(f.payer(), f.source(), f.qr()); var request = new TransferExecuteRequest(p, key());
        UUID[] txId = new UUID[1];
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            txId[0] = transfers.execute(request, f.payer().getEmail()).id();
            throw new IllegalStateException("Injected failure before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(transactions.findById(txId[0])).isEmpty();
        assertThat(ledger.findAllByTransactionId(txId[0])).isEmpty();
        assertThat(audit.findAllByTransactionId(txId[0])).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?", Long.class, txId[0])).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records where idempotency_key = ?", Long.class, request.idempotencyKey())).isZero();
        assertThat(qrRepository.findById(f.qr().id()).orElseThrow().getState()).isEqualTo(QrState.ACTIVE);
        assertThat(previewRepository.findById(p).orElseThrow().getConsumedAt()).isNull();
        assertThat(balance(f.source())).isEqualByComparingTo("5000");
        assertThat(balance(f.destination())).isEqualByComparingTo("0");
        assertFinancialEffect(transfers.execute(request, f.payer().getEmail()).id(), f.destination().getId());
    }

    @Test
    void revocationAndExpiryAfterPreviewPreventMoneyMovement() {
        var revoked = fixture(); var p1 = preview(revoked.payer(), revoked.source(), revoked.qr());
        qrService.revoke(revoked.qr().id(), revoked.owner().getId());
        assertThatThrownBy(() -> transfers.execute(new TransferExecuteRequest(p1, key()), revoked.payer().getEmail()))
                .isInstanceOfSatisfying(QrException.class, ex -> assertThat(ex.getCode()).isEqualTo("QR_REVOKED"));
        var expired = fixture(); var p2 = preview(expired.payer(), expired.source(), expired.qr());
        jdbc.update("update payment_qr_codes set created_at = now() - interval '1 hour', expires_at = now() - interval '1 second' where id = ?", expired.qr().id());
        assertThatThrownBy(() -> transfers.execute(new TransferExecuteRequest(p2, key()), expired.payer().getEmail()))
                .isInstanceOfSatisfying(QrException.class, ex -> assertThat(ex.getCode()).isEqualTo("QR_EXPIRED"));
        assertThat(balance(revoked.source())).isEqualByComparingTo("5000");
        assertThat(balance(expired.source())).isEqualByComparingTo("5000");
    }

    @Test
    void accountFreezeAndInsufficientFundsAfterPreviewLeaveRequestUnpaid() {
        var f = fixture(); var p = preview(f.payer(), f.source(), f.qr()); var request = new TransferExecuteRequest(p, key());
        jdbc.update("update accounts set status = 'FROZEN' where id = ?", f.destination().getId());
        assertThatThrownBy(() -> transfers.execute(request, f.payer().getEmail())).isInstanceOf(AccountNotActiveException.class);
        jdbc.update("update accounts set status = 'ACTIVE' where id = ?", f.destination().getId());
        jdbc.update("update accounts set balance = 100 where id = ?", f.source().getId());
        assertThatThrownBy(() -> transfers.execute(request, f.payer().getEmail())).isInstanceOf(InsufficientBalanceException.class);
        assertThat(qrRepository.findById(f.qr().id()).orElseThrow().getState()).isEqualTo(QrState.ACTIVE);
        assertThat(previewRepository.findById(p).orElseThrow().getConsumedAt()).isNull();
        assertThat(balance(f.destination())).isEqualByComparingTo("0");
    }

    @Test
    void paymentAndRevokeRaceHasOneCoherentTerminalOutcome() throws Exception {
        var f = fixture(); var request = new TransferExecuteRequest(preview(f.payer(), f.source(), f.qr()), key());
        var outcomes = race(() -> transfers.execute(request, f.payer().getEmail()), () -> qrService.revoke(f.qr().id(), f.owner().getId()));
        assertThat(outcomes.stream().filter(QrException.class::isInstance)).hasSize(1);
        var state = qrRepository.findById(f.qr().id()).orElseThrow().getState();
        if (state == QrState.PAID) {
            assertThat(outcomes.get(0)).isInstanceOf(TransactionResponse.class);
            assertFinancialEffect(((TransactionResponse) outcomes.get(0)).id(), f.destination().getId());
        } else {
            assertThat(state).isEqualTo(QrState.REVOKED);
            assertThat(balance(f.source())).isEqualByComparingTo("5000");
            assertThat(balance(f.destination())).isEqualByComparingTo("0");
        }
    }

    @Test
    void fixedPreviewRejectsOverridesSelectorsSelfTransferAndForeignSource() {
        var f = fixture();
        assertThatThrownBy(() -> previews.create(new TransferPreviewRequest(null, f.source().getId(), null, null,
                "1000", "VND", null, f.qr().id()), f.payer().getEmail())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> previews.create(new TransferPreviewRequest(null, f.source().getId(), f.destination().getId(), null,
                null, null, null, f.qr().id()), f.payer().getEmail())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preview(f.owner(), f.destination(), f.qr())).isInstanceOf(SelfTransferException.class);
        assertThatThrownBy(() -> preview(f.owner(), f.source(), f.qr())).isInstanceOf(com.actilazion.aries_transaction.common.exception.ForbiddenOperationException.class);
    }

    @Test
    void realHttpFlowUsesStringAmountsMaskedRecipientAndStableReplay() throws Exception {
        var f = fixture();
        var resolved = request("POST", "/qr-codes/resolve", f.payer(), json.writeValueAsString(new ResolveQrRequest(f.qr().payload())), null);
        assertThat(resolved.statusCode()).isEqualTo(200);
        assertThat(resolved.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(resolved.body()).doesNotContain(f.destination().getAccountNumber(), f.destination().getId().toString(), f.owner().getId().toString());
        assertThat(json.readTree(resolved.body()).path("data").path("amount").asString()).isEqualTo("1500.00");
        var preview = request("POST", "/transfers/preview", f.payer(),
                json.writeValueAsString(new TransferPreviewRequest(null, f.source().getId(), null, null, null, null, null, f.qr().id())), null);
        assertThat(preview.statusCode()).isEqualTo(200);
        var previewId = UUID.fromString(json.readTree(preview.body()).path("data").path("previewId").asString());
        String body = json.writeValueAsString(new TransferExecuteRequest(previewId, key()));
        var execute = request("POST", "/transfers", f.payer(), body, null);
        assertThat(execute.statusCode()).isEqualTo(200);
        var replay = request("POST", "/transfers", f.payer(), body, null);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replay.body()).path("data")).isEqualTo(json.readTree(execute.body()).path("data"));
    }

    @Test
    void realFilterChainRejectsAnonymousSuspendedAndForeignOwnerIncludingAdmin() throws Exception {
        var f = fixture(); var admin = user(); admin.setRole(Role.ADMIN); users.saveAndFlush(admin);
        for (String path : List.of("/qr-codes/resolve", "/qr-codes/" + f.qr().id() + "/revoke", "/accounts/" + f.destination().getId() + "/qr-codes")) {
            assertThat(request("POST", path, null, "{}", null).statusCode()).isEqualTo(401);
        }
        String accountPath = "/accounts/" + f.destination().getId() + "/qr-codes";
        assertThat(request("GET", accountPath, null, null, null).statusCode()).isEqualTo(401);
        for (User foreign : List.of(f.payer(), admin)) {
            assertThat(request("GET", accountPath, foreign, null, null).statusCode()).isEqualTo(404);
            assertThat(request("POST", accountPath, foreign, json.writeValueAsString(fixedRequest()), key()).statusCode()).isEqualTo(404);
            assertThat(request("POST", "/qr-codes/" + f.qr().id() + "/revoke", foreign, "{}", null).statusCode()).isEqualTo(404);
        }
        var token = jwt.generateToken(AuthenticatedUserPrincipal.from(f.payer()));
        jdbc.update("update users set is_active = false where id = ?", f.payer().getId());
        var response = http.send(HttpRequest.newBuilder(uri("/qr-codes/resolve")).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void httpCreationValidationRateLimitAndCorsAreEnforced() throws Exception {
        var owner = user(); var account = account(owner, "0"); String path = "/accounts/" + account.getId() + "/qr-codes";
        String body = json.writeValueAsString(fixedRequest()); var key = key();
        var first = request("POST", path, owner, body, key);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(json.readTree(request("POST", path, owner, body, key).body()).path("data"))
                .isEqualTo(json.readTree(first.body()).path("data"));
        assertThat(request("POST", path, owner, body, null).statusCode()).isEqualTo(400);
        assertThat(request("GET", path + "?size=101", owner, null, null).statusCode()).isEqualTo(400);
        var preflight = http.send(HttpRequest.newBuilder(uri(path)).header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Headers").orElseThrow().toLowerCase()).contains("idempotency-key");
        var caller = user();
        for (int i = 0; i < 30; i++) {
            assertThat(request("POST", "/qr-codes/resolve", caller, "{\"payload\":\"invalid\"}", null).statusCode()).isEqualTo(400);
        }
        var limited = request("POST", "/qr-codes/resolve", caller, "{\"payload\":\"invalid\"}", null);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    private void assertFinancialEffect(UUID tx, UUID destination) {
        assertThat(transactions.findById(tx)).isPresent();
        var entries = ledger.findAllByTransactionId(tx);
        assertThat(entries).hasSize(2);
        var debits = entries.stream().filter(e -> e.getDirection() == LedgerDirection.DEBIT).map(e -> e.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        var credits = entries.stream().filter(e -> e.getDirection() == LedgerDirection.CREDIT).map(e -> e.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo("1500"); assertThat(credits).isEqualByComparingTo(debits);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and event_type = 'TransferCompleted'", Long.class, tx)).isEqualTo(1);
        assertThat(audit.findAllByTransactionId(tx)).hasSize(2);
        assertThat(jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, destination)).isEqualByComparingTo("1500");
    }

    private Fixture fixture() {
        var owner = user(); var payer = user(); var source = account(payer, "5000"); var destination = account(owner, "0");
        var qr = qrService.create(destination.getId(), fixedRequest(), key(), owner.getId());
        return new Fixture(owner, payer, source, destination, qr);
    }
    private record Fixture(User owner, User payer, Account source, Account destination, QrResponse qr) {}
    private CreateQrRequest fixedRequest() { return new CreateQrRequest(QrType.PAYMENT_REQUEST, "1500.00", "VND", "Lunch"); }
    private UUID preview(User payer, Account source, QrResponse qr) {
        return previews.create(new TransferPreviewRequest(null, source.getId(), null, null, null, null, null, qr.id()), payer.getEmail()).previewId();
    }
    private String key() { return UUID.randomUUID().toString(); }
    private User user() {
        return users.saveAndFlush(User.builder().email(key() + "@qr.test").fullName("QR Test User").passwordHash(key()).role(Role.USER).build());
    }
    private Account account(User owner, String balance) {
        return accounts.saveAndFlush(Account.builder().user(owner).accountNumber(Long.toString(NUMBERS.incrementAndGet()))
                .accountType(AccountType.PERSONAL).status(AccountStatus.ACTIVE).currency("VND").balance(new BigDecimal(balance)).build());
    }
    private BigDecimal balance(Account account) { return accounts.findById(account.getId()).orElseThrow().getBalance(); }
    private URI uri(String path) { return URI.create("http://localhost:" + port + "/api/v1" + path); }
    private HttpResponse<String> request(String method, String path, User user, String body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
        if (user != null) builder.header("Authorization", "Bearer " + jwt.generateToken(AuthenticatedUserPrincipal.from(user)));
        if (key != null) builder.header("Idempotency-Key", key);
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> tasks = new ArrayList<>();
            for (Callable<?> action : List.of(first, second)) tasks.add(executor.submit(() -> {
                start.await();
                try { return action.call(); } catch (Exception ex) { return ex; }
            }));
            start.countDown();
            return List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS));
        }
    }
}
