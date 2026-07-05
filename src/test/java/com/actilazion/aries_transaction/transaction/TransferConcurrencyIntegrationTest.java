package com.actilazion.aries_transaction.transaction;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.persistence.AccountRepository;
import com.actilazion.aries_transaction.audit.persistence.AuditLogRepository;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.persistence.UserRepository;
import com.actilazion.aries_transaction.ledger.persistence.LedgerEntryRepository;
import com.actilazion.aries_transaction.outbox.persistence.OutboxEventRepository;
import com.actilazion.aries_transaction.transaction.application.IdempotencyService;
import com.actilazion.aries_transaction.transaction.application.TransferService;
import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.transaction.exception.InsufficientBalanceException;
import com.actilazion.aries_transaction.transaction.persistence.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class TransferConcurrencyIntegrationTest {
    @Autowired TransferService transferService;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired UserRepository userRepository;

    @MockitoBean
    IdempotencyService idempotencyService;

    private User sender;
    private User receiver;
    private Account senderAccount;
    private Account receiverAccount;

    @BeforeEach
    void setUp() {
        when(idempotencyService.tryConsume(anyString())).thenReturn(true);

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

    private List<Object> runConcurrently(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Object>> futures = List.of(
                    executor.submit(awaitStartThenRun(first, ready, start)),
                    executor.submit(awaitStartThenRun(second, ready, start))
            );

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
