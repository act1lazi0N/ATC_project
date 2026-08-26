package com.actilazion.aries_transaction.transaction;

import com.actilazion.aries_transaction.account.application.AccountService;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.domain.exception.AccountLimitExceededException;
import com.actilazion.aries_transaction.account.dto.AccountResponse;
import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.audit.domain.AuditEventType;
import com.actilazion.aries_transaction.audit.infrastructure.AuditLogRepository;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.ledger.domain.LedgerDirection;
import com.actilazion.aries_transaction.ledger.infrastructure.LedgerEntryRepository;
import com.actilazion.aries_transaction.outbox.infrastructure.OutboxEventRepository;
import com.actilazion.aries_transaction.transaction.application.TransferPreviewService;
import com.actilazion.aries_transaction.transaction.application.TransferService;
import com.actilazion.aries_transaction.transaction.domain.IdempotencyRecordStatus;
import com.actilazion.aries_transaction.transaction.domain.TransactionOperation;
import com.actilazion.aries_transaction.transaction.domain.TransferPreviewMode;
import com.actilazion.aries_transaction.transaction.domain.exception.IdempotencyConflictException;
import com.actilazion.aries_transaction.transaction.domain.exception.TransferPreviewUnavailableException;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.transaction.dto.TransferExecuteRequest;
import com.actilazion.aries_transaction.transaction.dto.TransferPreviewRequest;
import com.actilazion.aries_transaction.transaction.infrastructure.IdempotencyRecordRepository;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import com.actilazion.aries_transaction.transaction.infrastructure.TransferPreviewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ExternalTransferPostgresIntegrationTest {
    private static final AtomicLong ACCOUNT_NUMBERS = new AtomicLong(820_000_000_000L);
    private static final String FORCED_ROLLBACK_DESCRIPTION = "FORCED_ROLLBACK_EXTERNAL";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired TransferPreviewService transferPreviewService;
    @Autowired TransferService transferService;
    @Autowired AccountService accountService;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransferPreviewRepository transferPreviewRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired IdempotencyRecordRepository idempotencyRecordRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void executeAndReplay_createOneBalancedFinancialEffect() {
        User owner = user("execute-owner");
        Account source = account(owner, "5000.00");
        Account destination = account(user("execute-recipient"), "100.00");
        String key = UUID.randomUUID().toString();
        String description = "PG execute replay " + UUID.randomUUID();

        UUID previewId = preview(owner, source, destination, description);
        TransactionResponse first = transferService.execute(new TransferExecuteRequest(previewId, key), owner.getEmail());
        TransactionResponse replay = transferService.execute(new TransferExecuteRequest(previewId, key), owner.getEmail());
        UUID differentPreviewId = preview(owner, source, destination, description);

        assertThat(replay).isEqualTo(first);
        assertThatThrownBy(() -> transferService.execute(
                new TransferExecuteRequest(differentPreviewId, key), owner.getEmail()))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(accountRepository.findById(source.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("3500.00");
        assertThat(accountRepository.findById(destination.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1600.00");
        assertThat(transferPreviewRepository.findById(previewId).orElseThrow().getConsumedAt()).isNotNull();
        assertThat(transactionRepository.findAll()).filteredOn(tx -> description.equals(tx.getDescription())).hasSize(1);

        var entries = ledgerEntryRepository.findAllByTransactionId(first.id());
        assertThat(entries).hasSize(2);
        BigDecimal debits = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerDirection.DEBIT)
                .map(entry -> entry.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerDirection.CREDIT)
                .map(entry -> entry.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo("1500.00");
        assertThat(credits).isEqualByComparingTo(debits);

        assertThat(auditLogRepository.findAllByTransactionId(first.id()))
                .extracting(log -> log.getEventType())
                .containsExactlyInAnyOrder(AuditEventType.TRANSFER_INITIATED, AuditEventType.TRANSFER_COMPLETED);
        assertThat(outboxEventRepository.findByAggregateTypeAndAggregateIdAndEventType(
                "Transaction", first.id(), "TransferCompleted")).isPresent();
        assertThat(idempotencyRecordRepository.findByIdempotencyKeyAndOperationAndInitiatorEmail(
                key, TransactionOperation.TRANSFER.name(), owner.getEmail()))
                .hasValueSatisfying(record -> assertThat(record.getStatus()).isEqualTo(IdempotencyRecordStatus.COMPLETED));
    }

    @Test
    void concurrentExecute_samePreviewOnlyOneRequestMovesMoney() throws Exception {
        User owner = user("concurrent-preview-owner");
        Account source = account(owner, "5000.00");
        Account destination = account(user("concurrent-preview-recipient"), "0.00");
        String description = "PG concurrent preview " + UUID.randomUUID();
        UUID previewId = preview(owner, source, destination, description);

        List<Object> outcomes = runConcurrently(
                () -> transferService.execute(
                        new TransferExecuteRequest(previewId, UUID.randomUUID().toString()), owner.getEmail()),
                () -> transferService.execute(
                        new TransferExecuteRequest(previewId, UUID.randomUUID().toString()), owner.getEmail()));

        assertThat(outcomes).filteredOn(TransactionResponse.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(TransferPreviewUnavailableException.class::isInstance).hasSize(1);
        assertThat(accountRepository.findById(source.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("3500.00");
        assertThat(accountRepository.findById(destination.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1500.00");
        assertThat(transactionRepository.findAll()).filteredOn(tx -> description.equals(tx.getDescription())).hasSize(1);
        assertThat(transferPreviewRepository.findById(previewId).orElseThrow().getConsumedAt()).isNotNull();
    }

    @Test
    void concurrentFifthAccountBoundary_allowsExactlyOneCreation() throws Exception {
        User owner = user("concurrent-account-owner");
        for (int index = 0; index < 4; index++) {
            account(owner, "0.00");
        }

        List<Object> outcomes = runConcurrently(
                () -> accountService.create(accountRequest("Concurrent A"), owner.getEmail()),
                () -> accountService.create(accountRequest("Concurrent B"), owner.getEmail()));

        assertThat(outcomes).filteredOn(AccountResponse.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(AccountLimitExceededException.class::isInstance).hasSize(1);
        assertThat(accountRepository.countByUserIdAndStatus(owner.getId(), AccountStatus.ACTIVE)).isEqualTo(5);
    }

    @Test
    void outboxFailure_rollsBackEntireExternalTransfer() {
        User owner = user("rollback-owner");
        Account source = account(owner, "5000.00");
        Account destination = account(user("rollback-recipient"), "100.00");
        String key = UUID.randomUUID().toString();
        UUID previewId = preview(owner, source, destination, FORCED_ROLLBACK_DESCRIPTION);
        long ledgerCount = ledgerEntryRepository.count();
        long auditCount = auditLogRepository.count();
        long outboxCount = outboxEventRepository.count();

        installOutboxFailureTrigger();
        try {
            assertThatThrownBy(() -> transferService.execute(
                    new TransferExecuteRequest(previewId, key), owner.getEmail()))
                    .isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("forced external transfer outbox failure");
        } finally {
            removeOutboxFailureTrigger();
        }

        assertThat(accountRepository.findById(source.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("5000.00");
        assertThat(accountRepository.findById(destination.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("100.00");
        assertThat(transactionRepository.findAll())
                .noneMatch(tx -> FORCED_ROLLBACK_DESCRIPTION.equals(tx.getDescription()));
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerCount);
        assertThat(auditLogRepository.count()).isEqualTo(auditCount);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCount);
        assertThat(idempotencyRecordRepository.findByIdempotencyKeyAndOperationAndInitiatorEmail(
                key, TransactionOperation.TRANSFER.name(), owner.getEmail())).isEmpty();
        assertThat(transferPreviewRepository.findById(previewId).orElseThrow().getConsumedAt()).isNull();
    }

    private UUID preview(User owner, Account source, Account destination, String description) {
        return transferPreviewService.create(new TransferPreviewRequest(
                TransferPreviewMode.EXTERNAL,
                source.getId(),
                null,
                destination.getAccountNumber(),
                "1500.00",
                "VND",
                description
        ), owner.getEmail()).previewId();
    }

    private User user(String label) {
        String suffix = UUID.randomUUID().toString();
        return userRepository.saveAndFlush(User.builder()
                .fullName("Postgres " + label)
                .email(label + "-" + suffix + "@test.local")
                .passwordHash("hashed")
                .role(Role.USER)
                .build());
    }

    private Account account(User owner, String balance) {
        return accountRepository.saveAndFlush(Account.builder()
                .user(owner)
                .accountNumber(String.format("%012d", ACCOUNT_NUMBERS.getAndIncrement()))
                .accountType(AccountType.PERSONAL)
                .balance(new BigDecimal(balance))
                .currency("VND")
                .status(AccountStatus.ACTIVE)
                .build());
    }

    private CreateAccountRequest accountRequest(String description) {
        return new CreateAccountRequest(
                AccountType.PERSONAL,
                "VND",
                description,
                UUID.randomUUID().toString()
        );
    }

    @SafeVarargs
    private List<Object> runConcurrently(Callable<Object>... operations) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(operations.length);
        try {
            List<Future<Object>> futures = java.util.Arrays.stream(operations)
                    .map(operation -> executor.submit(() -> captureOutcome(start, operation)))
                    .toList();
            start.countDown();
            return futures.stream().map(this::await).toList();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Object captureOutcome(CountDownLatch start, Callable<Object> operation) throws Exception {
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            return operation.call();
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private Object await(Future<Object> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent operation did not finish", exception);
        }
    }

    private void installOutboxFailureTrigger() {
        removeOutboxFailureTrigger();
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_external_transfer_outbox_insert()
                RETURNS trigger AS $$
                BEGIN
                    IF NEW.payload ->> 'description' = 'FORCED_ROLLBACK_EXTERNAL' THEN
                        RAISE EXCEPTION 'forced external transfer outbox failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_external_transfer_outbox
                BEFORE INSERT ON outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_external_transfer_outbox_insert()
                """);
    }

    private void removeOutboxFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_external_transfer_outbox ON outbox_events");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_external_transfer_outbox_insert()");
    }
}
