package com.actilazion.aries_transaction.settlement;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.audit.application.AuditLogService;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.ledger.application.LedgerService;
import com.actilazion.aries_transaction.ledger.domain.LedgerDirection;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntryType;
import com.actilazion.aries_transaction.ledger.infrastructure.LedgerEntryRepository;
import com.actilazion.aries_transaction.outbox.application.OutboxEventService;
import com.actilazion.aries_transaction.settlement.application.SettlementService;
import com.actilazion.aries_transaction.settlement.application.SettlementServiceImpl;
import com.actilazion.aries_transaction.settlement.domain.PayoutStatus;
import com.actilazion.aries_transaction.settlement.domain.SettlementBatchStatus;
import com.actilazion.aries_transaction.settlement.domain.exception.NoSettlementCandidateException;
import com.actilazion.aries_transaction.settlement.domain.exception.SettlementIdempotencyConflictException;
import com.actilazion.aries_transaction.settlement.infrastructure.SettlementBatchRepository;
import com.actilazion.aries_transaction.settlement.infrastructure.SettlementItemRepository;
import com.actilazion.aries_transaction.transaction.application.IdempotencyService;
import com.actilazion.aries_transaction.transaction.application.TransferService;
import com.actilazion.aries_transaction.transaction.application.TransferServiceImpl;
import com.actilazion.aries_transaction.transaction.dto.RefundRequest;
import com.actilazion.aries_transaction.transaction.dto.ReversalRequest;
import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@Import({
        TransferServiceImpl.class,
        AuditLogService.class,
        OutboxEventService.class,
        LedgerService.class,
        IdempotencyService.class,
        SettlementServiceImpl.class
})
class SettlementServiceIntegrationTest {
    @Autowired TestEntityManager em;
    @Autowired TransferService transferService;
    @Autowired SettlementService settlementService;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired SettlementBatchRepository settlementBatchRepository;
    @Autowired SettlementItemRepository settlementItemRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;

    private User sender;
    private User operator;
    private Account senderAccount;
    private Account receiverAccount;

    @BeforeEach
    void setUp() {
        sender = userRepository.save(User.builder()
                .fullName("Settlement Sender")
                .email("settlement-sender@test.com")
                .passwordHash("hashed")
                .role(Role.MERCHANT)
                .build());

        User receiver = userRepository.save(User.builder()
                .fullName("Settlement Receiver")
                .email("settlement-receiver@test.com")
                .passwordHash("hashed")
                .role(Role.USER)
                .build());

        senderAccount = accountRepository.save(Account.builder()
                .user(sender)
                .accountNumber("SETTLE-SENDER")
                .accountType(AccountType.PERSONAL)
                .balance(new BigDecimal("10000000"))
                .currency("VND")
                .status(AccountStatus.ACTIVE)
                .build());

        receiverAccount = accountRepository.save(Account.builder()
                .user(receiver)
                .accountNumber("SETTLE-RECEIVER")
                .accountType(AccountType.PERSONAL)
                .balance(BigDecimal.ZERO)
                .currency("VND")
                .status(AccountStatus.ACTIVE)
                .build());

        User system = userRepository.save(User.builder()
                .fullName("Aries System")
                .email("system@aries.internal")
                .passwordHash("hashed")
                .role(Role.ADMIN)
                .build());

        operator = userRepository.save(User.builder()
                .fullName("Settlement Operator")
                .email("settlement-operator@test.com")
                .passwordHash("hashed")
                .role(Role.OPERATOR)
                .build());

        accountRepository.save(systemAccount(system, "CLEARING-VND", AccountType.CLEARING));
        accountRepository.save(systemAccount(system, "PAYABLE-VND", AccountType.RECEIVER_PAYABLE));
        accountRepository.save(systemAccount(system, "REVENUE-VND", AccountType.PLATFORM_REVENUE));

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("Create settlement batch calculates gross, fee, net, revenue, and payable")
    void createBatch_completedTransfers_success() {
        var first = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        var second = transferService.transfer(transferRequest("500000"), sender.getEmail());
        OffsetDateTime cutoff = second.completedAt().plusSeconds(1);

        var batch = settlementService.createBatch("VND", 200, "settle-key-1", cutoff, operator.getEmail());

        assertThat(batch.status()).isEqualTo(SettlementBatchStatus.PENDING);
        assertThat(batch.currency()).isEqualTo("VND");
        assertThat(batch.idempotencyKey()).isEqualTo("settle-key-1");
        assertThat(batch.cutoffCompletedAt()).isEqualTo(cutoff);
        assertThat(batch.grossAmount()).isEqualByComparingTo("1500000");
        assertThat(batch.feeAmount()).isEqualByComparingTo("30000");
        assertThat(batch.netAmount()).isEqualByComparingTo("1470000");
        assertThat(batch.items()).hasSize(2);
        assertThat(batch.items()).allSatisfy(item -> {
            assertThat(item.payoutStatus()).isEqualTo(PayoutStatus.PENDING);
            assertThat(item.platformRevenue()).isEqualByComparingTo(item.feeAmount());
            assertThat(item.receiverPayable()).isEqualByComparingTo(item.netAmount());
            assertThat(item.grossAmount()).isEqualByComparingTo(item.feeAmount().add(item.netAmount()));
            assertThat(item.receiverAccountId()).isEqualTo(receiverAccount.getId());
        });
        assertThat(batch.items().stream().map(item -> item.transactionId()))
                .containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(settlementBatchRepository.count()).isEqualTo(1);
        assertThat(settlementItemRepository.count()).isEqualTo(2);
        assertSettlementLedgerBalanced(first.id(), "1000000", "980000", "20000");
        assertSettlementLedgerBalanced(second.id(), "500000", "490000", "10000");
    }

    @Test
    @DisplayName("Already settled transactions are not settled twice")
    void createBatch_alreadySettled_throwsNoCandidate() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        OffsetDateTime cutoff = transfer.completedAt().plusSeconds(1);
        settlementService.createBatch("VND", 200, "settle-key-2", cutoff, operator.getEmail());

        assertThatThrownBy(() -> settlementService.createBatch(
                "VND",
                200,
                "settle-key-3",
                cutoff.plusSeconds(1),
                operator.getEmail()
        ))
                .isInstanceOf(NoSettlementCandidateException.class);
        assertThat(settlementBatchRepository.count()).isEqualTo(1);
        assertThat(settlementItemRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Reversed and refunded transfers are excluded from settlement candidates")
    void createBatch_reversedAndRefundedTransfers_excluded() {
        var reversed = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        var refunded = transferService.transfer(transferRequest("500000"), sender.getEmail());
        var eligible = transferService.transfer(transferRequest("250000"), sender.getEmail());
        OffsetDateTime cutoff = eligible.completedAt().plusSeconds(1);

        transferService.reverse(
                reversed.id(),
                new ReversalRequest(UUID.randomUUID().toString(), "Reverse before settlement"),
                operator.getEmail()
        );
        transferService.refund(
                refunded.id(),
                new RefundRequest(new BigDecimal("500000"), UUID.randomUUID().toString(), "Refund before settlement"),
                sender.getEmail()
        );

        var batch = settlementService.createBatch("VND", 200, "settle-key-4", cutoff, operator.getEmail());

        assertThat(batch.items()).hasSize(1);
        assertThat(batch.items().getFirst().transactionId()).isEqualTo(eligible.id());
        assertThat(batch.grossAmount()).isEqualByComparingTo("250000");
        assertThat(batch.feeAmount()).isEqualByComparingTo("5000");
        assertThat(batch.netAmount()).isEqualByComparingTo("245000");
    }

    @Test
    @DisplayName("Settlement cutoff excludes transactions completed after cutoff")
    void createBatch_cutoff_excludesLaterTransactions() {
        var included = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        OffsetDateTime cutoff = included.completedAt().plusSeconds(1);
        var excluded = transferService.transfer(transferRequest("500000"), sender.getEmail());
        transactionRepository.findById(excluded.id()).orElseThrow()
                .setCompletedAt(cutoff.plusDays(1));
        em.flush();

        var batch = settlementService.createBatch("VND", 200, "settle-key-5", cutoff, operator.getEmail());

        assertThat(batch.items()).hasSize(1);
        assertThat(batch.items().getFirst().transactionId()).isEqualTo(included.id());
        assertThat(batch.items().stream().map(item -> item.transactionId())).doesNotContain(excluded.id());
        assertThat(batch.grossAmount()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("Settlement batch creation is idempotent")
    void createBatch_sameIdempotencyKey_returnsOriginalBatch() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        OffsetDateTime cutoff = transfer.completedAt().plusSeconds(1);

        var first = settlementService.createBatch("VND", 200, "settle-key-6", cutoff, operator.getEmail());
        var second = settlementService.createBatch("VND", 200, "settle-key-6", cutoff, operator.getEmail());

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(settlementBatchRepository.count()).isEqualTo(1);
        assertThat(settlementItemRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.countByTransactionIdAndEntryType(
                transfer.id(),
                LedgerEntryType.SETTLEMENT
        )).isEqualTo(3);
    }

    @Test
    @DisplayName("Same settlement idempotency key with different request is rejected")
    void createBatch_sameIdempotencyKeyDifferentRequest_throwsConflict() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        OffsetDateTime cutoff = transfer.completedAt().plusSeconds(1);
        settlementService.createBatch("VND", 200, "settle-key-7", cutoff, operator.getEmail());

        assertThatThrownBy(() -> settlementService.createBatch(
                "VND",
                300,
                "settle-key-7",
                cutoff,
                operator.getEmail()
        ))
                .isInstanceOf(SettlementIdempotencyConflictException.class);
        assertThat(settlementBatchRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("User cannot create settlement batch through service layer")
    void createBatch_userRole_throwsAccessDenied() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        OffsetDateTime cutoff = transfer.completedAt().plusSeconds(1);

        assertThatThrownBy(() -> settlementService.createBatch(
                "VND",
                200,
                "settle-key-denied",
                cutoff,
                sender.getEmail()
        )).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
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

    private Account systemAccount(User user, String accountNumber, AccountType accountType) {
        return Account.builder()
                .user(user)
                .accountNumber(accountNumber)
                .accountType(accountType)
                .balance(BigDecimal.ZERO)
                .currency("VND")
                .status(AccountStatus.ACTIVE)
                .build();
    }

    private void assertSettlementLedgerBalanced(
            UUID transactionId,
            String grossAmount,
            String receiverPayable,
            String platformRevenue
    ) {
        var entries = ledgerEntryRepository.findAllByTransactionId(transactionId).stream()
                .filter(entry -> entry.getEntryType() == LedgerEntryType.SETTLEMENT)
                .toList();

        assertThat(entries).hasSize(3);
        assertThat(entries)
                .filteredOn(entry -> entry.getDirection() == LedgerDirection.DEBIT)
                .singleElement()
                .satisfies(entry -> assertThat(entry.getAmount()).isEqualByComparingTo(grossAmount));
        assertThat(entries)
                .filteredOn(entry -> entry.getDirection() == LedgerDirection.CREDIT)
                .anySatisfy(entry -> assertThat(entry.getAmount()).isEqualByComparingTo(receiverPayable))
                .anySatisfy(entry -> assertThat(entry.getAmount()).isEqualByComparingTo(platformRevenue));

        BigDecimal debit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerDirection.DEBIT)
                .map(entry -> entry.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerDirection.CREDIT)
                .map(entry -> entry.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).isEqualByComparingTo(credit);
    }
}
