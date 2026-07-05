package com.actilazion.aries_transaction.settlement;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.persistence.AccountRepository;
import com.actilazion.aries_transaction.audit.application.AuditLogService;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.persistence.UserRepository;
import com.actilazion.aries_transaction.ledger.application.LedgerService;
import com.actilazion.aries_transaction.outbox.application.OutboxEventService;
import com.actilazion.aries_transaction.settlement.application.SettlementService;
import com.actilazion.aries_transaction.settlement.application.SettlementServiceImpl;
import com.actilazion.aries_transaction.settlement.domain.PayoutStatus;
import com.actilazion.aries_transaction.settlement.domain.SettlementBatchStatus;
import com.actilazion.aries_transaction.settlement.exception.NoSettlementCandidateException;
import com.actilazion.aries_transaction.settlement.persistence.SettlementBatchRepository;
import com.actilazion.aries_transaction.settlement.persistence.SettlementItemRepository;
import com.actilazion.aries_transaction.transaction.application.IdempotencyService;
import com.actilazion.aries_transaction.transaction.application.TransferService;
import com.actilazion.aries_transaction.transaction.application.TransferServiceImpl;
import com.actilazion.aries_transaction.transaction.dto.RefundRequest;
import com.actilazion.aries_transaction.transaction.dto.ReversalRequest;
import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.transaction.persistence.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
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

    private User sender;
    private Account senderAccount;
    private Account receiverAccount;

    @BeforeEach
    void setUp() {
        sender = userRepository.save(User.builder()
                .fullName("Settlement Sender")
                .email("settlement-sender@test.com")
                .passwordHash("hashed")
                .role(Role.USER)
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

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("Create settlement batch calculates gross, fee, net, revenue, and payable")
    void createBatch_completedTransfers_success() {
        var first = transferService.transfer(transferRequest("1000000"), sender.getEmail());
        var second = transferService.transfer(transferRequest("500000"), sender.getEmail());

        var batch = settlementService.createBatch("VND", 200);

        assertThat(batch.status()).isEqualTo(SettlementBatchStatus.OPEN);
        assertThat(batch.currency()).isEqualTo("VND");
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
    }

    @Test
    @DisplayName("Already settled transactions are not settled twice")
    void createBatch_alreadySettled_throwsNoCandidate() {
        transferService.transfer(transferRequest("1000000"), sender.getEmail());
        settlementService.createBatch("VND", 200);

        assertThatThrownBy(() -> settlementService.createBatch("VND", 200))
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

        transferService.reverse(
                reversed.id(),
                new ReversalRequest(UUID.randomUUID().toString(), "Reverse before settlement"),
                sender.getEmail()
        );
        transferService.refund(
                refunded.id(),
                new RefundRequest(new BigDecimal("500000"), UUID.randomUUID().toString(), "Refund before settlement"),
                sender.getEmail()
        );

        var batch = settlementService.createBatch("VND", 200);

        assertThat(batch.items()).hasSize(1);
        assertThat(batch.items().getFirst().transactionId()).isEqualTo(eligible.id());
        assertThat(batch.grossAmount()).isEqualByComparingTo("250000");
        assertThat(batch.feeAmount()).isEqualByComparingTo("5000");
        assertThat(batch.netAmount()).isEqualByComparingTo("245000");
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
}
