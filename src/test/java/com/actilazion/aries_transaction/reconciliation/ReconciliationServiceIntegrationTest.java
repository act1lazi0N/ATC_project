package com.actilazion.aries_transaction.reconciliation;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.audit.application.AuditLogService;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.ledger.application.LedgerService;
import com.actilazion.aries_transaction.outbox.application.OutboxEventService;
import com.actilazion.aries_transaction.reconciliation.application.ReconciliationPolicyProperties;
import com.actilazion.aries_transaction.reconciliation.application.ReconciliationService;
import com.actilazion.aries_transaction.reconciliation.application.ReconciliationServiceImpl;
import com.actilazion.aries_transaction.reconciliation.application.ReportingTransactionSnapshotClient;
import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationExceptionType;
import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationRunStatus;
import com.actilazion.aries_transaction.reconciliation.dto.ReportingTransactionSnapshot;
import com.actilazion.aries_transaction.reconciliation.infrastructure.ReconciliationExceptionRepository;
import com.actilazion.aries_transaction.reconciliation.infrastructure.ReconciliationRunRepository;
import com.actilazion.aries_transaction.transaction.application.IdempotencyService;
import com.actilazion.aries_transaction.transaction.application.TransferService;
import com.actilazion.aries_transaction.transaction.application.TransferServiceImpl;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
        ReconciliationPolicyProperties.class,
        ReconciliationServiceImpl.class,
        ReconciliationServiceIntegrationTest.FakeReportingConfig.class
})
class ReconciliationServiceIntegrationTest {
    @Autowired TestEntityManager em;
    @Autowired TransferService transferService;
    @Autowired ReconciliationService reconciliationService;
    @Autowired FakeReportingSnapshotClient reportingSnapshotClient;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired ReconciliationRunRepository reconciliationRunRepository;
    @Autowired ReconciliationExceptionRepository reconciliationExceptionRepository;

    private User sender;
    private User operator;
    private Account senderAccount;
    private Account receiverAccount;

    @BeforeEach
    void setUp() {
        reportingSnapshotClient.snapshots = List.of();

        sender = userRepository.save(User.builder()
                .fullName("Reconciliation Sender")
                .email("reconciliation-sender@test.com")
                .passwordHash("hashed")
                .role(Role.USER)
                .build());

        User receiver = userRepository.save(User.builder()
                .fullName("Reconciliation Receiver")
                .email("reconciliation-receiver@test.com")
                .passwordHash("hashed")
                .role(Role.USER)
                .build());

        operator = userRepository.save(User.builder()
                .fullName("Reconciliation Operator")
                .email("reconciliation-operator@test.com")
                .passwordHash("hashed")
                .role(Role.OPERATOR)
                .build());

        senderAccount = accountRepository.save(Account.builder()
                .user(sender)
                .accountNumber("RECON-SENDER")
                .accountType(AccountType.PERSONAL)
                .balance(new BigDecimal("10000000"))
                .currency("VND")
                .status(AccountStatus.ACTIVE)
                .build());

        receiverAccount = accountRepository.save(Account.builder()
                .user(receiver)
                .accountNumber("RECON-RECEIVER")
                .accountType(AccountType.PERSONAL)
                .balance(BigDecimal.ZERO)
                .currency("VND")
                .status(AccountStatus.ACTIVE)
                .build());

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("Reconciliation run completes cleanly when reporting matches source")
    void reconcile_matchingReporting_noExceptions() {
        var first = transferService.transfer(transferRequest("100000"), sender.getEmail());
        var second = transferService.transfer(transferRequest("200000"), sender.getEmail());
        OffsetDateTime windowStart = first.completedAt().minusSeconds(1);
        OffsetDateTime windowEnd = OffsetDateTime.now();
        reportingSnapshotClient.snapshots = List.of(
                snapshot(first.id(), "100000", TransactionStatus.COMPLETED, first.completedAt()),
                snapshot(second.id(), "200000", TransactionStatus.COMPLETED, second.completedAt())
        );

        var run = reconciliationService.reconcile("vnd", windowStart, windowEnd, operator.getEmail());

        assertThat(run.status()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(run.sourceCount()).isEqualTo(2);
        assertThat(run.reportingCount()).isEqualTo(2);
        assertThat(run.exceptionCount()).isZero();
        assertThat(run.exceptions()).isEmpty();
        assertThat(reconciliationRunRepository.count()).isEqualTo(1);
        assertThat(reconciliationExceptionRepository.count()).isZero();
    }

    @Test
    @DisplayName("Reconciliation persists missing, duplicate, amount, status, and unexpected reporting exceptions")
    void reconcile_mismatchedReporting_persistsExceptions() {
        var missing = transferService.transfer(transferRequest("100000"), sender.getEmail());
        var duplicate = transferService.transfer(transferRequest("200000"), sender.getEmail());
        var amountMismatch = transferService.transfer(transferRequest("300000"), sender.getEmail());
        var statusMismatch = transferService.transfer(transferRequest("400000"), sender.getEmail());
        OffsetDateTime windowStart = missing.completedAt().minusSeconds(1);
        OffsetDateTime windowEnd = OffsetDateTime.now();

        reportingSnapshotClient.snapshots = List.of(
                snapshot(duplicate.id(), "200000", TransactionStatus.COMPLETED, duplicate.completedAt()),
                snapshot(duplicate.id(), "200000", TransactionStatus.COMPLETED, duplicate.completedAt()),
                snapshot(amountMismatch.id(), "300001", TransactionStatus.COMPLETED, amountMismatch.completedAt()),
                snapshot(statusMismatch.id(), "400000", TransactionStatus.FAILED, statusMismatch.completedAt()),
                snapshot(UUID.randomUUID(), "500000", TransactionStatus.COMPLETED, statusMismatch.completedAt())
        );

        var run = reconciliationService.reconcile("VND", windowStart, windowEnd, operator.getEmail());

        assertThat(run.status()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(run.sourceCount()).isEqualTo(4);
        assertThat(run.reportingCount()).isEqualTo(5);
        assertThat(run.exceptionCount()).isEqualTo(5);
        assertThat(run.exceptions())
                .extracting("exceptionType")
                .containsExactlyInAnyOrder(
                        ReconciliationExceptionType.MISSING_IN_REPORTING,
                        ReconciliationExceptionType.DUPLICATE_IN_REPORTING,
                        ReconciliationExceptionType.AMOUNT_MISMATCH,
                        ReconciliationExceptionType.STATUS_MISMATCH,
                        ReconciliationExceptionType.UNEXPECTED_IN_REPORTING
                );
        assertThat(reconciliationRunRepository.count()).isEqualTo(1);
        assertThat(reconciliationExceptionRepository.count()).isEqualTo(5);
    }

    @Test
    @DisplayName("User cannot run reconciliation through service layer")
    void reconcile_userRole_throwsAccessDenied() {
        var transfer = transferService.transfer(transferRequest("100000"), sender.getEmail());
        OffsetDateTime windowStart = transfer.completedAt().minusSeconds(1);
        OffsetDateTime windowEnd = OffsetDateTime.now();

        assertThatThrownBy(() -> reconciliationService.reconcile(
                "VND",
                windowStart,
                windowEnd,
                sender.getEmail()
        )).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    @DisplayName("Reconciliation rejects windows larger than configured maximum")
    void reconcile_windowTooLarge_throwsException() {
        OffsetDateTime windowEnd = OffsetDateTime.now().minusDays(1);
        OffsetDateTime windowStart = windowEnd.minusDays(32);

        assertThatThrownBy(() -> reconciliationService.reconcile(
                "VND",
                windowStart,
                windowEnd,
                operator.getEmail()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reconciliation window must not exceed");
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

    private ReportingTransactionSnapshot snapshot(
            UUID transactionId,
            String amount,
            TransactionStatus status,
            OffsetDateTime completedAt
    ) {
        return new ReportingTransactionSnapshot(
                transactionId,
                new BigDecimal(amount),
                "VND",
                status,
                completedAt
        );
    }

    @TestConfiguration
    static class FakeReportingConfig {
        @Bean
        FakeReportingSnapshotClient fakeReportingSnapshotClient() {
            return new FakeReportingSnapshotClient();
        }
    }

    static class FakeReportingSnapshotClient implements ReportingTransactionSnapshotClient {
        private List<ReportingTransactionSnapshot> snapshots = new ArrayList<>();

        @Override
        public List<ReportingTransactionSnapshot> fetchSnapshots(
                String currency,
                OffsetDateTime windowStart,
                OffsetDateTime windowEnd
        ) {
            return snapshots.stream()
                    .filter(snapshot -> snapshot.currency().equals(currency))
                    .filter(snapshot -> !snapshot.completedAt().isBefore(windowStart))
                    .filter(snapshot -> snapshot.completedAt().isBefore(windowEnd))
                    .toList();
        }
    }
}
