package com.actilazion.aries_transaction.outbox;

import com.actilazion.aries_transaction.account.domain.*;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.identity.domain.*;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.outbox.application.OutboxEventService;
import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.outbox.infrastructure.OutboxEventRepository;
import com.actilazion.aries_transaction.support.PostgresIntegrationTestSupport;
import com.actilazion.aries_transaction.transaction.domain.*;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class OutboxRecordingPostgresIntegrationTest extends PostgresIntegrationTestSupport {
    @Autowired OutboxEventService service;
    @Autowired OutboxEventRepository events;
    @Autowired TransactionRepository transactions;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void concurrentRecordingKeepsOneOriginalEventWithoutFailingEitherCaller() throws Exception {
        Transaction tx = transaction();
        OutboxEvent original = concurrentRecording(tx, false);
        var persisted = event(tx);
        assertThat(persisted.getId()).isEqualTo(original.getId());
        assertThat(persisted.getPayload()).isEqualTo(original.getPayload());
        assertThat(persisted.getStatus()).isEqualTo(original.getStatus());
        assertThat(events.findAll()).filteredOn(row -> row.getAggregateId().equals(tx.getId())).hasSize(1);
    }

    @Test
    void rolledBackRecorderDoesNotLoseConcurrentValidEventOrKeepPartialBalanceChange() throws Exception {
        Transaction tx = transaction();
        var originalBalance = accounts.findById(tx.getFromAccount().getId()).orElseThrow().getBalance();
        OutboxEvent rolledBack = concurrentRecording(tx, true);
        assertThat(event(tx).getId()).isNotEqualTo(rolledBack.getId());
        assertThat(events.findAll()).filteredOn(row -> row.getAggregateId().equals(tx.getId())).hasSize(1);
        assertThat(accounts.findById(tx.getFromAccount().getId()).orElseThrow().getBalance()).isEqualByComparingTo(originalBalance);
    }

    @Test
    void unrelatedOutboxPersistenceFailureStillRollsBackFinancialChanges() {
        Transaction tx = transaction();
        BigDecimal originalBalance = accounts.findById(tx.getFromAccount().getId()).orElseThrow().getBalance();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var account = accounts.findById(tx.getFromAccount().getId()).orElseThrow();
            account.setBalance(account.getBalance().subtract(BigDecimal.ONE));
            service.recordTransferCompleted(tx);
            event(tx).setPayload(null); // Required DB column: must fail and roll back the shared transaction.
            events.flush();
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(events.findAll()).noneMatch(row -> row.getAggregateId().equals(tx.getId()));
        assertThat(accounts.findById(tx.getFromAccount().getId()).orElseThrow().getBalance()).isEqualByComparingTo(originalBalance);
    }

    private OutboxEvent concurrentRecording(Transaction tx, boolean rollbackFirst) throws Exception {
        CountDownLatch recorded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                service.recordTransferCompleted(tx);
                if (rollbackFirst) {
                    var account = accounts.findById(tx.getFromAccount().getId()).orElseThrow();
                    account.setBalance(account.getBalance().subtract(BigDecimal.ONE));
                    status.setRollbackOnly();
                }
                var original = event(tx);
                events.flush();
                recorded.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return original;
            }));
            assertThat(recorded.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.recordTransferCompleted(tx));
            try {
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            OutboxEvent original = first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            return original;
        }
    }

    private OutboxEvent event(Transaction tx) {
        return events.findByAggregateTypeAndAggregateIdAndEventType("Transaction", tx.getId(), "TransferCompleted").orElseThrow();
    }

    private Transaction transaction() {
        User user = users.saveAndFlush(User.builder().fullName("Outbox recording test")
                .email(UUID.randomUUID() + "@test.local").passwordHash("hashed").role(Role.USER).isActive(true).build());
        Account from = account(user);
        Account to = account(user);
        Transaction tx = Transaction.builder().fromAccount(from).toAccount(to).initiatedBy(user)
                .amount(new BigDecimal("100.00")).currency("VND").status(TransactionStatus.PENDING)
                .operation(TransactionOperation.TRANSFER).idempotencyKey(UUID.randomUUID().toString()).build();
        tx.markCompleted(OffsetDateTime.now());
        return transactions.saveAndFlush(tx);
    }

    private Account account(User user) {
        return accounts.saveAndFlush(Account.builder().user(user).accountNumber(UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .accountType(AccountType.PERSONAL).balance(new BigDecimal("1000.00"))
                .currency("VND").status(AccountStatus.ACTIVE).build());
    }
}
