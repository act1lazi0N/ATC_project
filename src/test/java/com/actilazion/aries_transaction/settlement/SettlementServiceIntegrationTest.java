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
import com.actilazion.aries_transaction.settlement.domain.SettlementItem;
import com.actilazion.aries_transaction.settlement.domain.SettlementItemType;
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
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionOperation;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
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
                operator.getEmail()
        );

        var batch = settlementService.createBatch("VND", 200, "settle-key-4", cutoff, operator.getEmail());

        assertThat(batch.items()).hasSize(1);
        assertThat(batch.items().getFirst().transactionId()).isEqualTo(eligible.id());
        assertThat(batch.grossAmount()).isEqualByComparingTo("250000");
        assertThat(batch.feeAmount()).isEqualByComparingTo("5000");
        assertThat(batch.netAmount()).isEqualByComparingTo("245000");
    }

    @Test
    @DisplayName("Pre-settlement partial refund settles only the outstanding transfer amount")
    void createBatch_preSettlementPartialRefund_settlesOutstandingAmount() {
        var transfer = transferService.transfer(transferRequest("1000.00"), sender.getEmail());
        var refund = transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("400.00"), UUID.randomUUID().toString(), "Refund before settlement"),
                operator.getEmail()
        );

        var batch = settlementService.createBatch(
                "VND",
                200,
                "settle-key-pre-settlement-partial-refund",
                refund.completedAt().plusSeconds(1),
                operator.getEmail()
        );

        assertThat(batch.items()).hasSize(1);
        var item = batch.items().getFirst();
        assertThat(item.transactionId()).isEqualTo(transfer.id());
        assertThat(item.itemType()).isEqualTo(SettlementItemType.NORMAL);
        assertThat(item.receiverAccountId()).isEqualTo(receiverAccount.getId());
        assertThat(item.grossAmount()).isEqualByComparingTo("600.00");
        assertThat(item.feeAmount()).isEqualByComparingTo("12.00");
        assertThat(item.netAmount()).isEqualByComparingTo("588.00");
        assertThat(batch.grossAmount()).isEqualByComparingTo("600.00");
        assertThat(batch.feeAmount()).isEqualByComparingTo("12.00");
        assertThat(batch.netAmount()).isEqualByComparingTo("588.00");
        assertSettlementLedgerBalanced(transfer.id(), "600.00", "588.00", "12.00");
        assertThat(ledgerEntryRepository.countByTransactionIdAndEntryType(
                refund.id(),
                LedgerEntryType.ADJUSTMENT
        )).isZero();

        assertThatThrownBy(() -> settlementService.createBatch(
                "VND",
                200,
                "settle-key-pre-settlement-partial-refund-next",
                refund.completedAt().plusSeconds(2),
                operator.getEmail()
        )).isInstanceOf(NoSettlementCandidateException.class);
    }

    @Test
    @DisplayName("Refund completed after cutoff is settled only in a later adjustment")
    void createBatch_refundAfterCutoff_isNotIncludedInBackdatedBatch() {
        var transfer = transferService.transfer(transferRequest("1000.00"), sender.getEmail());
        var refund = transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("400.00"), UUID.randomUUID().toString(), "Refund after cutoff"),
                operator.getEmail()
        );
        OffsetDateTime cutoff = transfer.completedAt().plusSeconds(1);
        OffsetDateTime refundCompletedAt = cutoff.plusDays(1);
        transactionRepository.findById(refund.id()).orElseThrow().setCompletedAt(refundCompletedAt);
        em.flush();
        em.clear();

        var originalBatch = settlementService.createBatch(
                "VND",
                200,
                "settle-key-cutoff-before-refund",
                cutoff,
                operator.getEmail()
        );

        assertThat(originalBatch.items()).hasSize(1);
        assertThat(originalBatch.items().getFirst().transactionId()).isEqualTo(transfer.id());
        assertThat(originalBatch.items().getFirst().grossAmount()).isEqualByComparingTo("1000.00");

        var adjustmentBatch = settlementService.createBatch(
                "VND",
                200,
                "settle-key-cutoff-after-refund",
                refundCompletedAt.plusSeconds(1),
                operator.getEmail()
        );

        assertThat(adjustmentBatch.items()).hasSize(1);
        assertThat(adjustmentBatch.items().getFirst().transactionId()).isEqualTo(refund.id());
        assertThat(adjustmentBatch.items().getFirst().itemType()).isEqualTo(SettlementItemType.ADJUSTMENT);
        assertThat(adjustmentBatch.items().getFirst().grossAmount()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("Post-settlement refund after pre-settlement partial refund creates adjustment for new refund only")
    void createBatch_postSettlementRefundAfterPreSettlementPartialRefund_adjustsOnlyNewRefund() {
        var transfer = transferService.transfer(transferRequest("1000.00"), sender.getEmail());
        var preSettlementRefund = transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("400.00"), UUID.randomUUID().toString(), "Refund before settlement"),
                operator.getEmail()
        );
        settlementService.createBatch(
                "VND",
                200,
                "settle-key-mixed-refund-original",
                preSettlementRefund.completedAt().plusSeconds(1),
                operator.getEmail()
        );

        var postSettlementRefund = transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("100.00"), UUID.randomUUID().toString(), "Refund after settlement"),
                operator.getEmail()
        );

        var adjustment = settlementService.createBatch(
                "VND",
                200,
                "settle-key-mixed-refund-adjustment",
                postSettlementRefund.completedAt().plusSeconds(1),
                operator.getEmail()
        );

        assertThat(adjustment.items()).hasSize(1);
        var item = adjustment.items().getFirst();
        assertThat(item.transactionId()).isEqualTo(postSettlementRefund.id());
        assertThat(item.itemType()).isEqualTo(SettlementItemType.ADJUSTMENT);
        assertThat(item.grossAmount()).isEqualByComparingTo("100.00");
        assertThat(item.feeAmount()).isEqualByComparingTo("2.00");
        assertThat(item.netAmount()).isEqualByComparingTo("98.00");
        assertSettlementAdjustmentLedgerBalanced(postSettlementRefund.id(), "100.00", "98.00", "2.00");
        assertThat(ledgerEntryRepository.countByTransactionIdAndEntryType(
                preSettlementRefund.id(),
                LedgerEntryType.ADJUSTMENT
        )).isZero();
    }

    @Test
    @DisplayName("Post-settlement refund creates an adjustment item and reverse settlement ledger")
    void createBatch_postSettlementRefund_createsAdjustmentItemAndLedger() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        settlementService.createBatch(
                "VND",
                200,
                "settle-key-original-refund",
                transfer.completedAt().plusSeconds(1),
                operator.getEmail()
        );

        var refund = transferService.refund(
                transfer.id(),
                new RefundRequest(new BigDecimal("500000"), UUID.randomUUID().toString(), "Refund after settlement"),
                operator.getEmail()
        );

        var adjustment = settlementService.createBatch(
                "VND",
                200,
                "settle-key-adjustment-refund",
                refund.completedAt().plusSeconds(1),
                operator.getEmail()
        );

        assertThat(adjustment.items()).hasSize(1);
        var item = adjustment.items().getFirst();
        assertThat(item.transactionId()).isEqualTo(refund.id());
        assertThat(item.itemType()).isEqualTo(SettlementItemType.ADJUSTMENT);
        assertThat(item.receiverAccountId()).isEqualTo(receiverAccount.getId());
        assertThat(item.grossAmount()).isEqualByComparingTo("500000");
        assertThat(item.feeAmount()).isEqualByComparingTo("10000");
        assertThat(item.netAmount()).isEqualByComparingTo("490000");
        assertThat(adjustment.grossAmount()).isEqualByComparingTo("500000");
        assertThat(adjustment.feeAmount()).isEqualByComparingTo("10000");
        assertThat(adjustment.netAmount()).isEqualByComparingTo("490000");
        assertSettlementAdjustmentLedgerBalanced(refund.id(), "500000", "490000", "10000");
    }

    @Test
    @DisplayName("Final post-settlement refund adjustment takes remaining rounded fee")
    void createBatch_splitRefundAdjustment_usesRemainingFeeOnFinalRefund() {
        var transfer = transferService.transfer(transferRequest("1000.00"), sender.getEmail());
        Transaction original = transactionRepository.findById(transfer.id()).orElseThrow();
        settlementService.createBatch(
                "VND",
                1,
                "settle-key-original-rounding",
                transfer.completedAt().plusSeconds(1),
                operator.getEmail()
        );

        OffsetDateTime firstCompletedAt = transfer.completedAt().plusSeconds(2);
        Transaction firstRefund = completedRefund(original, "333.00", firstCompletedAt);
        var firstAdjustment = settlementService.createBatch(
                "VND",
                1,
                "settle-key-adjustment-rounding-1",
                firstCompletedAt.plusSeconds(1),
                operator.getEmail()
        );

        OffsetDateTime secondCompletedAt = firstCompletedAt.plusSeconds(2);
        Transaction secondRefund = completedRefund(original, "333.00", secondCompletedAt);
        var secondAdjustment = settlementService.createBatch(
                "VND",
                1,
                "settle-key-adjustment-rounding-2",
                secondCompletedAt.plusSeconds(1),
                operator.getEmail()
        );

        OffsetDateTime thirdCompletedAt = secondCompletedAt.plusSeconds(2);
        Transaction thirdRefund = completedRefund(original, "334.00", thirdCompletedAt);
        var thirdAdjustment = settlementService.createBatch(
                "VND",
                1,
                "settle-key-adjustment-rounding-3",
                thirdCompletedAt.plusSeconds(1),
                operator.getEmail()
        );

        assertThat(firstAdjustment.items().getFirst().feeAmount()).isEqualByComparingTo("0.03");
        assertThat(secondAdjustment.items().getFirst().feeAmount()).isEqualByComparingTo("0.03");
        assertThat(thirdAdjustment.items().getFirst().feeAmount()).isEqualByComparingTo("0.04");
        assertThat(thirdAdjustment.items().getFirst().netAmount()).isEqualByComparingTo("333.96");
        assertThat(totalAdjustmentFee(original)).isEqualByComparingTo("0.10");
        assertSettlementAdjustmentLedgerBalanced(firstRefund.getId(), "333.00", "332.97", "0.03");
        assertSettlementAdjustmentLedgerBalanced(secondRefund.getId(), "333.00", "332.97", "0.03");
        assertSettlementAdjustmentLedgerBalanced(thirdRefund.getId(), "334.00", "333.96", "0.04");
    }

    @Test
    @DisplayName("Post-settlement reversal creates a full adjustment item")
    void createBatch_postSettlementReversal_createsAdjustmentItem() {
        var transfer = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        settlementService.createBatch(
                "VND",
                200,
                "settle-key-original-reversal",
                transfer.completedAt().plusSeconds(1),
                operator.getEmail()
        );

        var reversal = transferService.reverse(
                transfer.id(),
                new ReversalRequest(UUID.randomUUID().toString(), "Reverse after settlement"),
                operator.getEmail()
        );

        var adjustment = settlementService.createBatch(
                "VND",
                200,
                "settle-key-adjustment-reversal",
                reversal.completedAt().plusSeconds(1),
                operator.getEmail()
        );

        assertThat(adjustment.items()).hasSize(1);
        var item = adjustment.items().getFirst();
        assertThat(item.transactionId()).isEqualTo(reversal.id());
        assertThat(item.itemType()).isEqualTo(SettlementItemType.ADJUSTMENT);
        assertThat(item.receiverAccountId()).isEqualTo(receiverAccount.getId());
        assertThat(item.grossAmount()).isEqualByComparingTo("1000000");
        assertThat(item.feeAmount()).isEqualByComparingTo("20000");
        assertThat(item.netAmount()).isEqualByComparingTo("980000");
        assertSettlementAdjustmentLedgerBalanced(reversal.id(), "1000000", "980000", "20000");
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

    private Transaction completedRefund(Transaction original, String amount, OffsetDateTime completedAt) {
        Transaction refund = Transaction.builder()
                .fromAccount(original.getToAccount())
                .toAccount(original.getFromAccount())
                .initiatedBy(operator)
                .amount(new BigDecimal(amount))
                .currency(original.getCurrency())
                .operation(TransactionOperation.REFUND)
                .idempotencyKey(UUID.randomUUID().toString())
                .description("Settlement rounding regression refund")
                .originalTransaction(original)
                .status(TransactionStatus.PENDING)
                .build();
        refund.markCompleted(completedAt);
        return transactionRepository.saveAndFlush(refund);
    }

    private BigDecimal totalAdjustmentFee(Transaction original) {
        return settlementItemRepository.findAllByTransaction_OriginalTransaction_Id(original.getId()).stream()
                .map(SettlementItem::getFeeAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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

    private void assertSettlementAdjustmentLedgerBalanced(
            UUID transactionId,
            String grossAmount,
            String receiverPayable,
            String platformRevenue
    ) {
        var entries = ledgerEntryRepository.findAllByTransactionId(transactionId).stream()
                .filter(entry -> entry.getEntryType() == LedgerEntryType.ADJUSTMENT)
                .toList();

        assertThat(entries).hasSize(3);
        assertThat(entries)
                .filteredOn(entry -> entry.getDirection() == LedgerDirection.CREDIT)
                .singleElement()
                .satisfies(entry -> assertThat(entry.getAmount()).isEqualByComparingTo(grossAmount));
        assertThat(entries)
                .filteredOn(entry -> entry.getDirection() == LedgerDirection.DEBIT)
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
