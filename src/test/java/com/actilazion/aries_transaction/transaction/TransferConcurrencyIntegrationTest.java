package com.actilazion.aries_transaction.transaction;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.audit.infrastructure.AuditLogRepository;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.ledger.infrastructure.LedgerEntryRepository;
import com.actilazion.aries_transaction.outbox.infrastructure.OutboxEventRepository;
import com.actilazion.aries_transaction.support.PostgresIntegrationTestSupport;
import com.actilazion.aries_transaction.transaction.application.TransferService;
import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.transaction.domain.exception.InsufficientBalanceException;
import com.actilazion.aries_transaction.transaction.infrastructure.IdempotencyRecordRepository;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TransferConcurrencyIntegrationTest extends PostgresIntegrationTestSupport {
    @Autowired TransferService transferService;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired IdempotencyRecordRepository idempotencyRecordRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired UserRepository userRepository;

    private User sender;
    private User receiver;
    private Account senderAccount;
    private Account receiverAccount;

    @BeforeEach
    void setUp() {
        sender = createUser("sender-" + UUID.randomUUID() + "@test.com", "Sender");
        receiver = createUser("receiver-" + UUID.randomUUID() + "@test.com", "Receiver");
        senderAccount = createAccount(sender, new BigDecimal("1000"));
        receiverAccount = createAccount(receiver, new BigDecimal("1000"));
    }

    @AfterEach
    void tearDown() {
        auditLogRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        outboxEventRepository.deleteAll();
        idempotencyRecordRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Concurrent debit from same account: only one succeeds when balance is insufficient")
    void concurrentDebitFromSameAccount_onlyOneSucceedsIfBalanceInsufficient() throws Exception {
        senderAccount.setBalance(new BigDecimal("100"));
        receiverAccount.setBalance(BigDecimal.ZERO);
        accountRepository.save(senderAccount);
        accountRepository.save(receiverAccount);

        User secondReceiver = createUser("receiver-" + UUID.randomUUID() + "@test.com", "Second Receiver");
        Account secondReceiverAccount = createAccount(secondReceiver, BigDecimal.ZERO);

        TransferRequest firstRequest = transferRequest(senderAccount, receiverAccount, new BigDecimal("80"));
        TransferRequest secondRequest = transferRequest(senderAccount, secondReceiverAccount, new BigDecimal("80"));

        List<Object> results = runConcurrently(
                () -> transferService.transfer(firstRequest, sender.getEmail()),
                () -> transferService.transfer(secondRequest, sender.getEmail())
        );

        assertThat(results).filteredOn(TransactionResponse.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(InsufficientBalanceException.class::isInstance).hasSize(1);

        Account updatedSender = accountRepository.findById(senderAccount.getId()).orElseThrow();
        Account updatedReceiver = accountRepository.findById(receiverAccount.getId()).orElseThrow();
        Account updatedSecondReceiver = accountRepository.findById(secondReceiverAccount.getId()).orElseThrow();

        assertThat(updatedSender.getBalance()).isEqualByComparingTo("20");
        assertThat(List.of(updatedReceiver.getBalance(), updatedSecondReceiver.getBalance()))
                .anySatisfy(balance -> assertThat(balance).isEqualByComparingTo("80"))
                .anySatisfy(balance -> assertThat(balance).isEqualByComparingTo("0"));
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Opposite transfers: deterministic lock ordering prevents deadlock")
    void oppositeTransfers_lockOrderingPreventsDeadlock() throws Exception {
        TransferRequest firstRequest = transferRequest(senderAccount, receiverAccount, new BigDecimal("100"));
        TransferRequest secondRequest = transferRequest(receiverAccount, senderAccount, new BigDecimal("100"));

        List<Object> results = runConcurrently(
                () -> transferService.transfer(firstRequest, sender.getEmail()),
                () -> transferService.transfer(secondRequest, receiver.getEmail())
        );

        assertThat(results).filteredOn(TransactionResponse.class::isInstance).hasSize(2);
        assertThat(accountRepository.findById(senderAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000");
        assertThat(accountRepository.findById(receiverAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000");
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository.count()).isEqualTo(4);
        assertThat(outboxEventRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Concurrent same idempotency key returns one financial effect")
    void concurrentSameIdempotencyKeySameRequest_returnsOriginalResponse() throws Exception {
        TransferRequest request = transferRequest(senderAccount, receiverAccount, new BigDecimal("100"));

        List<Object> results = runConcurrently(
                () -> transferService.transfer(request, sender.getEmail()),
                () -> transferService.transfer(request, sender.getEmail())
        );

        List<TransactionResponse> responses = results.stream()
                .filter(TransactionResponse.class::isInstance)
                .map(TransactionResponse.class::cast)
                .toList();

        assertThat(responses).hasSize(2);
        assertThat(responses)
                .extracting(TransactionResponse::id)
                .containsOnly(responses.getFirst().id());
        assertThat(accountRepository.findById(senderAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("900");
        assertThat(accountRepository.findById(receiverAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1100");
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Stress: many concurrent debits from same account preserve money invariants")
    void stressConcurrentDebitsFromSameAccount_preservesMoneyInvariants() throws Exception {
        senderAccount.setBalance(new BigDecimal("1000"));
        accountRepository.save(senderAccount);

        List<Account> receivers = IntStream.range(0, 20)
                .mapToObj(index -> createAccount(
                        createUser("stress-receiver-" + index + "-" + UUID.randomUUID() + "@test.com", "Stress Receiver " + index),
                        BigDecimal.ZERO
                ))
                .toList();
        List<Callable<?>> tasks = receivers.stream()
                .<Callable<?>>map(receiverAccount -> () -> transferService.transfer(
                        transferRequest(senderAccount, receiverAccount, new BigDecimal("75")),
                        sender.getEmail()
                ))
                .toList();

        List<Object> results = runConcurrently(tasks);

        long successCount = results.stream().filter(TransactionResponse.class::isInstance).count();
        long insufficientCount = results.stream().filter(InsufficientBalanceException.class::isInstance).count();

        assertThat(successCount).isEqualTo(13);
        assertThat(insufficientCount).isEqualTo(7);
        assertThat(accountRepository.findById(senderAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("25");
        BigDecimal totalReceived = receivers.stream()
                .map(account -> accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalReceived).isEqualByComparingTo("975");
        assertThat(transactionRepository.count()).isEqualTo(successCount);
        assertThat(ledgerEntryRepository.count()).isEqualTo(successCount * 2);
        assertThat(outboxEventRepository.count()).isEqualTo(successCount);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(successCount);
    }

    @Test
    @DisplayName("Stress: many opposite transfers do not deadlock and preserve balances")
    void stressOppositeTransfers_noDeadlockAndPreservesBalances() throws Exception {
        List<Callable<?>> tasks = IntStream.range(0, 20)
                .<Callable<?>>mapToObj(index -> () -> {
                    Account fromAccount = index % 2 == 0 ? senderAccount : receiverAccount;
                    Account toAccount = index % 2 == 0 ? receiverAccount : senderAccount;
                    String actorEmail = index % 2 == 0 ? sender.getEmail() : receiver.getEmail();
                    return transferService.transfer(
                            transferRequest(fromAccount, toAccount, new BigDecimal("10")),
                            actorEmail
                    );
                })
                .toList();

        List<Object> results = runConcurrently(tasks);

        assertThat(results).filteredOn(TransactionResponse.class::isInstance).hasSize(20);
        assertThat(accountRepository.findById(senderAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000");
        assertThat(accountRepository.findById(receiverAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000");
        assertThat(transactionRepository.count()).isEqualTo(20);
        assertThat(ledgerEntryRepository.count()).isEqualTo(40);
        assertThat(outboxEventRepository.count()).isEqualTo(20);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(20);
    }

    private List<Object> runConcurrently(Callable<?> first, Callable<?> second) throws Exception {
        return runConcurrently(List.of(first, second));
    }

    private List<Object> runConcurrently(List<Callable<?>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Object>> futures = tasks.stream()
                    .map(task -> executor.submit(awaitStartThenRun(task, ready, start)))
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<Object> awaitStartThenRun(Callable<?> task, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                return task.call();
            } catch (Exception ex) {
                return ex;
            }
        };
    }

    private User createUser(String email, String fullName) {
        return userRepository.save(User.builder()
                .fullName(fullName)
                .email(email)
                .passwordHash("hashed")
                .role(Role.USER)
                .build());
    }

    private Account createAccount(User user, BigDecimal balance) {
        return accountRepository.save(Account.builder()
                .user(user)
                .accountNumber("ACC" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .accountType(AccountType.PERSONAL)
                .balance(balance)
                .currency("VND")
                .status(AccountStatus.ACTIVE)
                .build());
    }

    private TransferRequest transferRequest(Account fromAccount, Account toAccount, BigDecimal amount) {
        return new TransferRequest(
                fromAccount.getId().toString(),
                toAccount.getId().toString(),
                amount,
                UUID.randomUUID().toString(),
                "VND",
                null
        );
    }
}
