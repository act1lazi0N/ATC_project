package com.actilazion.aries_transaction.transaction;

import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntry;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.ledger.domain.LedgerDirection;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntryType;
import com.actilazion.aries_transaction.outbox.domain.OutboxEventStatus;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.exception.AccountNotActiveException;
import com.actilazion.aries_transaction.transaction.exception.IdempotencyConflictException;
import com.actilazion.aries_transaction.transaction.exception.InsufficientBalanceException;
import com.actilazion.aries_transaction.transaction.exception.SelfTransferException;
import com.actilazion.aries_transaction.audit.application.AuditLogService;
import com.actilazion.aries_transaction.ledger.application.LedgerService;
import com.actilazion.aries_transaction.outbox.application.OutboxEventService;
import com.actilazion.aries_transaction.transaction.application.IdempotencyService;
import com.actilazion.aries_transaction.transaction.application.TransferService;
import com.actilazion.aries_transaction.account.persistence.AccountRepository;
import com.actilazion.aries_transaction.audit.persistence.AuditLogRepository;
import com.actilazion.aries_transaction.ledger.persistence.LedgerEntryRepository;
import com.actilazion.aries_transaction.outbox.persistence.OutboxEventRepository;
import com.actilazion.aries_transaction.transaction.persistence.TransactionRepository;
import com.actilazion.aries_transaction.identity.persistence.UserRepository;
import com.actilazion.aries_transaction.transaction.application.TransferServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@ActiveProfiles("test")
@Import({TransferServiceImpl.class, AuditLogService.class, OutboxEventService.class, LedgerService.class})
public class TransferServiceIntegrationTest {
    @Autowired
    TestEntityManager em;
    @Autowired TransferService      transferService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    TransactionRepository transactionRepository;
    @Autowired
    AuditLogRepository auditLogRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;
    @Autowired
    LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    IdempotencyService idempotencyService;

    private User sender;
    private Account senderAccount;
    private Account receiverAccount;

    @TestConfiguration
    static class TestConfig {
        @Bean
        IdempotencyService idempotencyService() {
            return mock(IdempotencyService.class);
        }
    }

    @BeforeEach
    void setUp() {
        when(idempotencyService.tryConsume(anyString())).thenReturn(true);

        sender = userRepository.save(User.builder()
                .fullName("Nguyen Van A")
                .email("sender@test.com")
                .passwordHash("hashed")
                .role(Role.USER)
                .build());

        User receiver = userRepository.save(User.builder()
                .fullName("Tran Thi B")
                .email("receiver@test.com")
                .passwordHash("hashed")
                .role(Role.USER)
                .build());

        senderAccount = accountRepository.save(Account.builder()
                .user(sender)
                .accountNumber("ACC-001")
                .accountType(AccountType.PERSONAL)
                .balance(new BigDecimal("5000000"))
                .currency("VND")
                .status(AccountStatus.ACTIVE)
                .build());

        receiverAccount = accountRepository.save(Account.builder()
                .user(receiver)
                .accountNumber("ACC-002")
                .accountType(AccountType.PERSONAL)
                .balance(new BigDecimal("1000000"))
                .currency("VND")
                .status(AccountStatus.ACTIVE)
                .build());

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("Transfered successfullly: Correct balance debit/credit, status COMPLETED")
    void transfer_success() {
        var request = new TransferRequest(
                senderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal("1000000"),
                UUID.randomUUID().toString(),
                "VND",
                "Trả tiền ăn"
        );

        var response = transferService.transfer(request, sender.getEmail());
        //Check response
        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(response.amount()).isEqualByComparingTo("1000000");
        assertThat(response.completedAt()).isNotNull();

        //Check balance
        em.flush();
        em.clear();
        Account updatedSender = accountRepository.findById(senderAccount.getId()).orElseThrow();
        Account updatedReceiver = accountRepository.findById(receiverAccount.getId()).orElseThrow();

        assertThat(updatedSender.getBalance()).isEqualByComparingTo("4000000");
        assertThat(updatedReceiver.getBalance()).isEqualByComparingTo("2000000");
    }

    @Test
    @DisplayName("Transferred successfully: creates balanced transfer ledger entries")
    void transfer_success_ledgerEntriesCreated() {
        var request = new TransferRequest(
                senderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal("1250000"),
                UUID.randomUUID().toString(),
                "VND",
                "Ledger invariant test"
        );

        var response = transferService.transfer(request, sender.getEmail());

        em.flush();
        em.clear();

        List<LedgerEntry> entries = ledgerEntryRepository.findAllByTransactionId(response.id());
        assertThat(entries).hasSize(2);

        LedgerEntry debit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerDirection.DEBIT)
                .findFirst()
                .orElseThrow();
        LedgerEntry credit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerDirection.CREDIT)
                .findFirst()
                .orElseThrow();

        assertThat(debit.getAccount().getId()).isEqualTo(senderAccount.getId());
        assertThat(credit.getAccount().getId()).isEqualTo(receiverAccount.getId());
        assertThat(debit.getAmount()).isEqualByComparingTo("1250000");
        assertThat(credit.getAmount()).isEqualByComparingTo("1250000");
        assertThat(debit.getAmount()).isEqualByComparingTo(credit.getAmount());
        assertThat(debit.getCurrency()).isEqualTo("VND");
        assertThat(credit.getCurrency()).isEqualTo("VND");
        assertThat(debit.getEntryType()).isEqualTo(LedgerEntryType.TRANSFER);
        assertThat(credit.getEntryType()).isEqualTo(LedgerEntryType.TRANSFER);
        assertThat(debit.getTransaction().getId()).isEqualTo(response.id());
        assertThat(credit.getTransaction().getId()).isEqualTo(response.id());
    }

    @Test
    @DisplayName("Transferred successfully: writes one TransferCompleted outbox event")
    void transfer_success_outboxEventCreated() {
        var request = new TransferRequest(
                senderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal("750000"),
                UUID.randomUUID().toString(),
                "VND",
                "Reporting sync test"
        );

        var response = transferService.transfer(request, sender.getEmail());

        em.flush();
        em.clear();

        var events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);

        var event = events.getFirst();
        assertThat(event.getAggregateType()).isEqualTo("Transaction");
        assertThat(event.getAggregateId()).isEqualTo(response.id());
        assertThat(event.getEventType()).isEqualTo("TransferCompleted");
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getPublishedAt()).isNull();

        assertThat(event.getPayload())
                .containsEntry("transactionId", response.id().toString())
                .containsEntry("fromAccountId", senderAccount.getId().toString())
                .containsEntry("toAccountId", receiverAccount.getId().toString())
                .containsEntry("userId", sender.getId().toString())
                .containsEntry("fromUserFullName", "Nguyen Van A")
                .containsEntry("toUserFullName", "Tran Thi B")
                .containsEntry("fromAccountNumber", "ACC-001")
                .containsEntry("toAccountNumber", "ACC-002")
                .containsEntry("amount", "750000")
                .containsEntry("currency", "VND")
                .containsEntry("status", "COMPLETED")
                .containsEntry("description", "Reporting sync test");
        assertThat(event.getPayload().get("createdAt")).isNotNull();
        assertThat(event.getPayload().get("completedAt")).isNotNull();
    }

    @Test
    @DisplayName("Transferred Successfully: must have 2 audit log (INITIATED + COMPLETED)")
    void transfer_success_auditLogsCreated() {
        var request = new TransferRequest(
                senderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal("500000"),
                UUID.randomUUID().toString(),
                "VND",
                null
        );

        var response = transferService.transfer(request, sender.getEmail());

        var logs = auditLogRepository.findAllByTransactionId(response.id());
        assertThat(logs).hasSize(2);
        assertThat(logs.stream().map(l -> l.getEventType().name()))
                .containsExactlyInAnyOrder("TRANSFER_INITIATED", "TRANSFER_COMPLETED");
    }

    @Test
    @DisplayName("Insufficient balance: InsufficientBalanceException, balance unchange ")
    void transfer_insufficientBalance_throwsException() {
        var request = new TransferRequest(
                senderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal("9999999"),
                UUID.randomUUID().toString(),
                "VND",
                null
        );

        assertThatThrownBy(() -> transferService.transfer(request, sender.getEmail()))
                .isInstanceOf(InsufficientBalanceException.class);

        em.clear();
        assertThat(accountRepository.findById(senderAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("5000000");
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    @DisplayName("Account has been frozen -> AccountNotActiveException")
    void transfer_frozenAccount_throwsException() {
        senderAccount.setStatus(AccountStatus.FROZEN);
        accountRepository.save(senderAccount);
        em.flush();
        em.clear();

        var request = new TransferRequest(
                senderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal("100000"),
                UUID.randomUUID().toString(),
                "VND",
                null
        );

        assertThatThrownBy(() -> transferService.transfer(request, sender.getEmail()))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    @DisplayName("Self transfer -> SelfTransferException")
    void transfer_selfTransfer_throwsException() {
        var request = new TransferRequest(
                senderAccount.getId().toString(),
                senderAccount.getId().toString(),
                new BigDecimal("100000"),
                UUID.randomUUID().toString(),
                "VND",
                null
        );

        assertThatThrownBy(() -> transferService.transfer(request, sender.getEmail()))
                .isInstanceOf(SelfTransferException.class);
    }

    @Test
    @DisplayName("Duplicated Idempotency key with same request -> returns original response")
    void transfer_duplicateIdempotencyKey_returnsOriginalResponse() {
        String sameKey = UUID.randomUUID().toString();
        when(idempotencyService.tryConsume(sameKey))
                .thenReturn(true)
                .thenReturn(false);

        var request = new TransferRequest(
                senderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal("100000"),
                sameKey,
                "VND",
                null
        );

        // 1# Successful transfer
        var firstResponse = transferService.transfer(request, sender.getEmail());

        // 2# Duplicate retry should return original result, not reject or transfer again.
        var secondResponse = transferService.transfer(request, sender.getEmail());

        // Only 1 transaction should be created
        assertThat(secondResponse).isEqualTo(firstResponse);
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);

        em.flush();
        em.clear();
        assertThat(accountRepository.findById(senderAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("4900000");
        assertThat(accountRepository.findById(receiverAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1100000");
    }

    @Test
    @DisplayName("Duplicated Idempotency key with different request -> IdempotencyConflictException")
    void transfer_duplicateIdempotencyKeyWithDifferentRequest_throwsConflict() {
        String sameKey = UUID.randomUUID().toString();

        var originalRequest = new TransferRequest(
                senderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal("100000"),
                sameKey,
                "VND",
                null
        );
        var conflictingRequest = new TransferRequest(
                senderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal("200000"),
                sameKey,
                "VND",
                null
        );

        transferService.transfer(originalRequest, sender.getEmail());

        assertThatThrownBy(() -> transferService.transfer(conflictingRequest, sender.getEmail()))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Exact balance success -> balance return to 0")
    void transfer_exactBalance_success() {
        var request = new TransferRequest(
                senderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal("5000000"),
                UUID.randomUUID().toString(),
                "VND",
                null
        );

        var response = transferService.transfer(request, sender.getEmail());

        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);

        em.flush();
        em.clear();
        assertThat(accountRepository.findById(senderAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("0");
    }

}
