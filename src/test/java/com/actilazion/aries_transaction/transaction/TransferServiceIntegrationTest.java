package com.actilazion.aries_transaction.transaction;

import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.transaction.dto.RefundRequest;
import com.actilazion.aries_transaction.transaction.dto.ReversalRequest;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntry;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.ledger.domain.LedgerDirection;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntryType;
import com.actilazion.aries_transaction.outbox.domain.OutboxEventStatus;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.transaction.domain.IdempotencyRecordStatus;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.domain.exception.AccountNotActiveException;
import com.actilazion.aries_transaction.transaction.domain.exception.CurrencyMismatchException;
import com.actilazion.aries_transaction.transaction.domain.exception.IdempotencyConflictException;
import com.actilazion.aries_transaction.transaction.domain.exception.InsufficientBalanceException;
import com.actilazion.aries_transaction.transaction.domain.exception.InvalidTransactionStateTransitionException;
import com.actilazion.aries_transaction.transaction.domain.exception.RefundAmountExceededException;
import com.actilazion.aries_transaction.transaction.domain.exception.SelfTransferException;
import com.actilazion.aries_transaction.audit.application.AuditLogService;
import com.actilazion.aries_transaction.ledger.application.LedgerService;
import com.actilazion.aries_transaction.outbox.application.OutboxEventService;
import com.actilazion.aries_transaction.transaction.application.IdempotencyService;
import com.actilazion.aries_transaction.transaction.application.TransferService;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.audit.infrastructure.AuditLogRepository;
import com.actilazion.aries_transaction.ledger.infrastructure.LedgerEntryRepository;
import com.actilazion.aries_transaction.outbox.infrastructure.OutboxEventRepository;
import com.actilazion.aries_transaction.transaction.infrastructure.IdempotencyRecordRepository;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.transaction.application.TransferServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@Import({TransferServiceImpl.class, AuditLogService.class, OutboxEventService.class, LedgerService.class, IdempotencyService.class})
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
    IdempotencyRecordRepository idempotencyRecordRepository;
    @Autowired
    UserRepository userRepository;

    private User sender;
    private User receiver;
    private User operator;
    private User admin;
    private User outsider;
    private Account senderAccount;
    private Account receiverAccount;

    @BeforeEach
    void setUp() {
        sender = userRepository.save(User.builder()
                .fullName("Nguyen Van A")
                .email("sender@test.com")
                .passwordHash("hashed")
                .role(Role.MERCHANT)
                .build());

        receiver = userRepository.save(User.builder()
                .fullName("Tran Thi B")
                .email("receiver@test.com")
                .passwordHash("hashed")
                .role(Role.USER)
                .build());

        operator = userRepository.save(User.builder()
                .fullName("Operations User")
                .email("operator@test.com")
                .passwordHash("hashed")
                .role(Role.OPERATOR)
                .build());

        admin = userRepository.save(User.builder()
                .fullName("Admin User")
                .email("admin@test.com")
                .passwordHash("hashed")
                .role(Role.ADMIN)
                .build());

        outsider = userRepository.save(User.builder()
                .fullName("Outside User")
                .email("outsider@test.com")
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
    @DisplayName("Transfer from another user's account is rejected")
    void transfer_fromForeignAccount_throwsAccessDenied() {
        var request = new TransferRequest(
                senderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal("1000000"),
                UUID.randomUUID().toString(),
                "VND",
                "Unauthorized debit"
        );

        assertThatThrownBy(() -> transferService.transfer(request, receiver.getEmail()))
                .isInstanceOf(AccessDeniedException.class);

        em.clear();
        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(accountRepository.findById(senderAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("5000000");
        assertThat(accountRepository.findById(receiverAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000000");
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

        assertThat(debit.getAccountId()).isEqualTo(senderAccount.getId());
        assertThat(credit.getAccountId()).isEqualTo(receiverAccount.getId());
        assertThat(debit.getAmount()).isEqualByComparingTo("1250000");
        assertThat(credit.getAmount()).isEqualByComparingTo("1250000");
        assertThat(debit.getAmount()).isEqualByComparingTo(credit.getAmount());
        assertThat(debit.getCurrency()).isEqualTo("VND");
        assertThat(credit.getCurrency()).isEqualTo("VND");
        assertThat(debit.getEntryType()).isEqualTo(LedgerEntryType.TRANSFER);
        assertThat(credit.getEntryType()).isEqualTo(LedgerEntryType.TRANSFER);
        assertThat(debit.getTransactionId()).isEqualTo(response.id());
        assertThat(credit.getTransactionId()).isEqualTo(response.id());
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
    @DisplayName("Different account currencies -> CurrencyMismatchException and no money movement")
    void transfer_differentAccountCurrencies_throwsCurrencyMismatch() {
        receiverAccount.setCurrency("USD");
        accountRepository.save(receiverAccount);
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
                .isInstanceOf(CurrencyMismatchException.class);

        em.clear();
        assertThat(transactionRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
        assertThat(accountRepository.findById(senderAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("5000000");
        assertThat(accountRepository.findById(receiverAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("Request currency different from account currency -> CurrencyMismatchException and no money movement")
    void transfer_requestCurrencyDifferentFromAccountCurrency_throwsCurrencyMismatch() {
        var request = new TransferRequest(
                senderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal("100000"),
                UUID.randomUUID().toString(),
                "USD",
                null
        );

        assertThatThrownBy(() -> transferService.transfer(request, sender.getEmail()))
                .isInstanceOf(CurrencyMismatchException.class);

        em.clear();
        assertThat(transactionRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
        assertThat(accountRepository.findById(senderAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("5000000");
        assertThat(accountRepository.findById(receiverAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000000");
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
    @DisplayName("Foreign user cannot replay another user's idempotent transfer response")
    void transfer_duplicateIdempotencyKeyForeignUser_throwsAccessDenied() {
        String sameKey = UUID.randomUUID().toString();
        var request = new TransferRequest(
                senderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal("100000"),
                sameKey,
                "VND",
                null
        );
        transferService.transfer(request, sender.getEmail());

        assertThatThrownBy(() -> transferService.transfer(request, receiver.getEmail()))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Same idempotency key can be used by different owners without collision")
    void transfer_sameIdempotencyKeyDifferentOwner_success() {
        Account outsiderAccount = accountRepository.save(Account.builder()
                .user(outsider)
                .accountNumber("ACC-OUTSIDER")
                .accountType(AccountType.PERSONAL)
                .balance(new BigDecimal("1000000"))
                .currency("VND")
                .status(AccountStatus.ACTIVE)
                .build());
        em.flush();
        String sameKey = UUID.randomUUID().toString();

        var senderRequest = new TransferRequest(
                senderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal("100000"),
                sameKey,
                "VND",
                "sender transfer"
        );
        var outsiderRequest = new TransferRequest(
                outsiderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal("100000"),
                sameKey,
                "VND",
                "outsider transfer"
        );

        var senderResponse = transferService.transfer(senderRequest, sender.getEmail());
        var outsiderResponse = transferService.transfer(outsiderRequest, outsider.getEmail());

        assertThat(outsiderResponse.id()).isNotEqualTo(senderResponse.id());
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(2);
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

    @Test
    @DisplayName("Reverse completed transfer: creates compensating transaction and reversal ledger")
    void reverse_completedTransfer_success() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());

        var reversal = transferService.reverse(
                transfer.id(),
                new ReversalRequest(UUID.randomUUID().toString(), "Reverse mistaken transfer"),
                operator.getEmail()
        );

        em.flush();
        em.clear();

        var original = transactionRepository.findById(transfer.id()).orElseThrow();
        assertThat(original.getStatus()).isEqualTo(TransactionStatus.REVERSED);

        var reversalTx = transactionRepository.findById(reversal.id()).orElseThrow();
        assertThat(reversalTx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(reversalTx.getOriginalTransaction().getId()).isEqualTo(transfer.id());
        assertThat(reversalTx.getFromAccount().getId()).isEqualTo(receiverAccount.getId());
        assertThat(reversalTx.getToAccount().getId()).isEqualTo(senderAccount.getId());
        assertThat(reversalTx.getAmount()).isEqualByComparingTo("1000000");

        assertThat(accountRepository.findById(senderAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("5000000");
        assertThat(accountRepository.findById(receiverAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000000");

        List<LedgerEntry> reversalEntries = ledgerEntryRepository.findAllByTransactionId(reversal.id());
        assertThat(reversalEntries).hasSize(2);
        assertThat(reversalEntries).allSatisfy(entry -> assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.REVERSAL));
        assertThat(reversalEntries.stream().filter(entry -> entry.getDirection() == LedgerDirection.DEBIT).findFirst().orElseThrow().getAccountId())
                .isEqualTo(receiverAccount.getId());
        assertThat(reversalEntries.stream().filter(entry -> entry.getDirection() == LedgerDirection.CREDIT).findFirst().orElseThrow().getAccountId())
                .isEqualTo(senderAccount.getId());
        assertThat(outboxEventRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Admin can reverse a completed transfer")
    void reverse_adminRole_success() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());

        var reversal = transferService.reverse(
                transfer.id(),
                new ReversalRequest(UUID.randomUUID().toString(), "Admin reversal"),
                admin.getEmail()
        );

        em.flush();
        em.clear();

        assertThat(reversal.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(transactionRepository.findById(transfer.id()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.REVERSED);
    }

    @Test
    @DisplayName("Merchant cannot reverse a completed transfer")
    void reverse_merchantRole_throwsAccessDenied() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());

        assertThatThrownBy(() -> transferService.reverse(
                transfer.id(),
                new ReversalRequest(UUID.randomUUID().toString(), "Unauthorized reversal"),
                sender.getEmail()
        )).isInstanceOf(AccessDeniedException.class);

        em.flush();
        em.clear();

        var original = transactionRepository.findById(transfer.id()).orElseThrow();
        assertThat(original.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(accountRepository.findById(senderAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("4000000");
        assertThat(accountRepository.findById(receiverAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("2000000");
    }

    @Test
    @DisplayName("Double reversal is rejected")
    void reverse_alreadyReversed_throwsInvalidState() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        transferService.reverse(
                transfer.id(),
                new ReversalRequest(UUID.randomUUID().toString(), null),
                operator.getEmail()
        );

        assertThatThrownBy(() -> transferService.reverse(
                transfer.id(),
                new ReversalRequest(UUID.randomUUID().toString(), null),
                operator.getEmail()
        )).isInstanceOf(InvalidTransactionStateTransitionException.class);

        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository.count()).isEqualTo(4);
        assertThat(outboxEventRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Reverse duplicate idempotency key with same request returns original response")
    void reverse_duplicateIdempotencyKeySameRequest_returnsOriginalResponse() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        String sameKey = UUID.randomUUID().toString();
        var request = new ReversalRequest(sameKey, "Reverse once");

        var firstResponse = transferService.reverse(transfer.id(), request, operator.getEmail());
        var secondResponse = transferService.reverse(transfer.id(), request, operator.getEmail());

        assertThat(secondResponse).isEqualTo(firstResponse);
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository.count()).isEqualTo(4);
        assertThat(outboxEventRepository.count()).isEqualTo(2);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(2);
        assertThat(idempotencyRecordRepository.findByIdempotencyKey(sameKey).orElseThrow().getStatus())
                .isEqualTo(IdempotencyRecordStatus.COMPLETED);
    }

    @Test
    @DisplayName("Reverse duplicate idempotency key with different request throws conflict")
    void reverse_duplicateIdempotencyKeyDifferentRequest_throwsConflict() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        String sameKey = UUID.randomUUID().toString();

        transferService.reverse(
                transfer.id(),
                new ReversalRequest(sameKey, "Reverse once"),
                operator.getEmail()
        );

        assertThatThrownBy(() -> transferService.reverse(
                transfer.id(),
                new ReversalRequest(sameKey, "Different reason"),
                operator.getEmail()
        )).isInstanceOf(IdempotencyConflictException.class);

        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository.count()).isEqualTo(4);
        assertThat(outboxEventRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Reverse rejects non-completed transaction states")
    void reverse_nonCompletedStates_throwsInvalidState() {
        for (TransactionStatus status : List.of(
                TransactionStatus.PENDING,
                TransactionStatus.FAILED,
                TransactionStatus.REVERSED,
                TransactionStatus.REFUNDED,
                TransactionStatus.PARTIALLY_REFUNDED
        )) {
            Transaction tx = persistTransactionWithStatus(status);
            String idempotencyKey = UUID.randomUUID().toString();

            assertThatThrownBy(() -> transferService.reverse(
                    tx.getId(),
                    new ReversalRequest(idempotencyKey, null),
                    operator.getEmail()
            )).isInstanceOf(InvalidTransactionStateTransitionException.class);
        }
    }

    @Test
    @DisplayName("Reverse rejects inactive refund source account")
    void reverse_inactiveReceiverAccount_throwsAccountNotActive() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        receiverAccount.setStatus(AccountStatus.FROZEN);
        accountRepository.save(receiverAccount);
        em.flush();
        em.clear();
        String idempotencyKey = UUID.randomUUID().toString();

        assertThatThrownBy(() -> transferService.reverse(
                transfer.id(),
                new ReversalRequest(idempotencyKey, null),
                operator.getEmail()
        )).isInstanceOf(AccountNotActiveException.class);

        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Reverse rejects insufficient balance on refund source account")
    void reverse_receiverInsufficientBalance_throwsInsufficientBalance() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        receiverAccount.setBalance(new BigDecimal("100000"));
        accountRepository.save(receiverAccount);
        em.flush();
        em.clear();
        String idempotencyKey = UUID.randomUUID().toString();

        assertThatThrownBy(() -> transferService.reverse(
                transfer.id(),
                new ReversalRequest(idempotencyKey, null),
                operator.getEmail()
        )).isInstanceOf(InsufficientBalanceException.class);

        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Partial then full refund updates original status and creates refund ledger")
    void refund_partialThenFull_success() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());

        var partialRefund = transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("400000"), UUID.randomUUID().toString(), "Partial refund"),
                sender.getEmail()
        );

        em.flush();
        em.clear();

        var partiallyRefunded = transactionRepository.findById(transfer.id()).orElseThrow();
        assertThat(partiallyRefunded.getStatus()).isEqualTo(TransactionStatus.PARTIALLY_REFUNDED);
        assertThat(partiallyRefunded.getRefundedAmount()).isEqualByComparingTo("400000");
        assertThat(partialRefund.originalTransactionId()).isEqualTo(transfer.id());

        List<LedgerEntry> partialEntries = ledgerEntryRepository.findAllByTransactionId(partialRefund.id());
        assertThat(partialEntries).hasSize(2);
        assertThat(partialEntries).allSatisfy(entry -> assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.REFUND));

        var fullRefund = transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("600000"), UUID.randomUUID().toString(), "Remaining refund"),
                sender.getEmail()
        );

        em.flush();
        em.clear();

        var refunded = transactionRepository.findById(transfer.id()).orElseThrow();
        assertThat(refunded.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
        assertThat(refunded.getRefundedAmount()).isEqualByComparingTo("1000000");
        assertThat(fullRefund.originalTransactionId()).isEqualTo(transfer.id());
        assertThat(accountRepository.findById(senderAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("5000000");
        assertThat(accountRepository.findById(receiverAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000000");
        assertThat(transactionRepository.count()).isEqualTo(3);
        assertThat(ledgerEntryRepository.count()).isEqualTo(6);
        assertThat(outboxEventRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("Operator can refund another merchant's transaction")
    void refund_operatorRole_success() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());

        var refund = transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("400000"), UUID.randomUUID().toString(), "Operator refund"),
                operator.getEmail()
        );

        em.flush();
        em.clear();

        assertThat(refund.status()).isEqualTo(TransactionStatus.COMPLETED);
        var original = transactionRepository.findById(transfer.id()).orElseThrow();
        assertThat(original.getStatus()).isEqualTo(TransactionStatus.PARTIALLY_REFUNDED);
        assertThat(original.getRefundedAmount()).isEqualByComparingTo("400000");
    }

    @Test
    @DisplayName("User cannot refund another user's transaction")
    void refund_userRole_throwsAccessDenied() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());

        assertThatThrownBy(() -> transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("400000"), UUID.randomUUID().toString(), "Unauthorized refund"),
                receiver.getEmail()
        )).isInstanceOf(AccessDeniedException.class);

        em.flush();
        em.clear();

        var original = transactionRepository.findById(transfer.id()).orElseThrow();
        assertThat(original.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(original.getRefundedAmount()).isEqualByComparingTo("0");
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(accountRepository.findById(senderAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("4000000");
        assertThat(accountRepository.findById(receiverAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("2000000");
    }

    @Test
    @DisplayName("Admin cannot refund unless granted operator policy")
    void refund_adminRole_throwsAccessDenied() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());

        assertThatThrownBy(() -> transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("400000"), UUID.randomUUID().toString(), "Admin refund"),
                admin.getEmail()
        )).isInstanceOf(AccessDeniedException.class);

        em.flush();
        em.clear();

        var original = transactionRepository.findById(transfer.id()).orElseThrow();
        assertThat(original.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(original.getRefundedAmount()).isEqualByComparingTo("0");
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Refund amount greater than remaining amount is rejected")
    void refund_amountGreaterThanRemaining_throwsException() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("400000"), UUID.randomUUID().toString(), null),
                sender.getEmail()
        );

        assertThatThrownBy(() -> transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("700000"), UUID.randomUUID().toString(), null),
                sender.getEmail()
        )).isInstanceOf(RefundAmountExceededException.class);

        var original = transactionRepository.findById(transfer.id()).orElseThrow();
        assertThat(original.getStatus()).isEqualTo(TransactionStatus.PARTIALLY_REFUNDED);
        assertThat(original.getRefundedAmount()).isEqualByComparingTo("400000");
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository.count()).isEqualTo(4);
        assertThat(outboxEventRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Refund duplicate idempotency key with same request returns original response")
    void refund_duplicateIdempotencyKeySameRequest_returnsOriginalResponse() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        String sameKey = UUID.randomUUID().toString();
        var request = new RefundRequest(new BigDecimal("400000"), sameKey, "Refund once");

        var firstResponse = transferService.refund(transfer.id(), request, sender.getEmail());
        var secondResponse = transferService.refund(transfer.id(), request, sender.getEmail());

        assertThat(secondResponse).isEqualTo(firstResponse);
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository.count()).isEqualTo(4);
        assertThat(outboxEventRepository.count()).isEqualTo(2);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(2);
        assertThat(idempotencyRecordRepository.findByIdempotencyKey(sameKey).orElseThrow().getStatus())
                .isEqualTo(IdempotencyRecordStatus.COMPLETED);
        assertThat(transactionRepository.findById(transfer.id()).orElseThrow().getRefundedAmount())
                .isEqualByComparingTo("400000");
    }

    @Test
    @DisplayName("Refund duplicate idempotency key with different request throws conflict")
    void refund_duplicateIdempotencyKeyDifferentRequest_throwsConflict() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        String sameKey = UUID.randomUUID().toString();

        transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("400000"), sameKey, "Refund once"),
                sender.getEmail()
        );

        assertThatThrownBy(() -> transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("500000"), sameKey, "Refund once"),
                sender.getEmail()
        )).isInstanceOf(IdempotencyConflictException.class);

        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository.count()).isEqualTo(4);
        assertThat(outboxEventRepository.count()).isEqualTo(2);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(2);
        assertThat(idempotencyRecordRepository.findByIdempotencyKey(sameKey).orElseThrow().getStatus())
                .isEqualTo(IdempotencyRecordStatus.COMPLETED);
    }

    @Test
    @DisplayName("Refund rejects non-refundable transaction states")
    void refund_nonRefundableStates_throwsInvalidState() {
        for (TransactionStatus status : List.of(
                TransactionStatus.PENDING,
                TransactionStatus.FAILED,
                TransactionStatus.REVERSED,
                TransactionStatus.REFUNDED
        )) {
            Transaction tx = persistTransactionWithStatus(status);
            String idempotencyKey = UUID.randomUUID().toString();

            assertThatThrownBy(() -> transferService.refund(
                    tx.getId(),
                    new RefundRequest(new BigDecimal("100000"), idempotencyKey, null),
                    sender.getEmail()
            )).isInstanceOf(InvalidTransactionStateTransitionException.class);
        }
    }

    @Test
    @DisplayName("Refund rejects inactive refund source account")
    void refund_inactiveReceiverAccount_throwsAccountNotActive() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        receiverAccount.setStatus(AccountStatus.FROZEN);
        accountRepository.save(receiverAccount);
        em.flush();
        em.clear();
        String idempotencyKey = UUID.randomUUID().toString();

        assertThatThrownBy(() -> transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("400000"), idempotencyKey, null),
                sender.getEmail()
        )).isInstanceOf(AccountNotActiveException.class);

        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Refund rejects insufficient balance on refund source account")
    void refund_receiverInsufficientBalance_throwsInsufficientBalance() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        receiverAccount.setBalance(new BigDecimal("100000"));
        accountRepository.save(receiverAccount);
        em.flush();
        em.clear();
        String idempotencyKey = UUID.randomUUID().toString();

        assertThatThrownBy(() -> transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("400000"), idempotencyKey, null),
                sender.getEmail()
        )).isInstanceOf(InsufficientBalanceException.class);

        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Transaction detail is visible to sender and receiver")
    void getById_participantUsers_success() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());

        var senderView = transferService.getById(transfer.id(), sender.getEmail());
        var receiverView = transferService.getById(transfer.id(), receiver.getEmail());

        assertThat(senderView.id()).isEqualTo(transfer.id());
        assertThat(receiverView.id()).isEqualTo(transfer.id());
    }

    @Test
    @DisplayName("Transaction detail is visible to operator")
    void getById_operatorRole_success() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());

        var operatorView = transferService.getById(transfer.id(), operator.getEmail());

        assertThat(operatorView.id()).isEqualTo(transfer.id());
    }

    @Test
    @DisplayName("Transaction detail is hidden from unrelated user")
    void getById_unrelatedUser_throwsAccessDenied() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());

        assertThatThrownBy(() -> transferService.getById(transfer.id(), outsider.getEmail()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Account transaction history is visible to account owner")
    void getByAccount_ownerUser_success() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());

        var page = transferService.getByAccount(receiverAccount.getId(), PageRequest.of(0, 20), receiver.getEmail());

        assertThat(page.getContent())
                .extracting(TransactionResponse::id)
                .containsExactly(transfer.id());
    }

    @Test
    @DisplayName("Account transaction history is visible to operator")
    void getByAccount_operatorRole_success() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());

        var page = transferService.getByAccount(receiverAccount.getId(), PageRequest.of(0, 20), operator.getEmail());

        assertThat(page.getContent())
                .extracting(TransactionResponse::id)
                .containsExactly(transfer.id());
    }

    @Test
    @DisplayName("Account transaction history is hidden from non-owner")
    void getByAccount_nonOwnerUser_throwsAccessDenied() {
        transferService.transfer(transferRequest("1000000"), sender.getEmail());

        assertThatThrownBy(() -> transferService.getByAccount(receiverAccount.getId(), PageRequest.of(0, 20), sender.getEmail()))
                .isInstanceOf(AccessDeniedException.class);
    }

    private TransferRequest transferRequest(String amount) {
        return new TransferRequest(
                senderAccount.getId().toString(),
                receiverAccount.getId().toString(),
                new BigDecimal(amount),
                UUID.randomUUID().toString(),
                "VND",
                null
        );
    }

    private Transaction persistTransactionWithStatus(TransactionStatus status) {
        Transaction tx = Transaction.builder()
                .fromAccount(senderAccount)
                .toAccount(receiverAccount)
                .initiatedBy(sender)
                .amount(new BigDecimal("100000"))
                .currency("VND")
                .idempotencyKey(UUID.randomUUID().toString())
                .status(status)
                .build();
        return transactionRepository.saveAndFlush(tx);
    }

}
