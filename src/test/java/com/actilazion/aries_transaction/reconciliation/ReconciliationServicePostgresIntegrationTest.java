package com.actilazion.aries_transaction.reconciliation;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.reconciliation.application.ReconciliationService;
import com.actilazion.aries_transaction.reconciliation.application.ReportingTransactionSnapshotClient;
import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationRunStatus;
import com.actilazion.aries_transaction.reconciliation.dto.ReportingTransactionSnapshot;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(ReconciliationServicePostgresIntegrationTest.FakeReportingConfig.class)
class ReconciliationServicePostgresIntegrationTest {
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
        registry.add("app.reconciliation.expected-lag", () -> "PT5M");
        registry.add("app.reconciliation.max-window", () -> "P31D");
    }

    @Autowired ReconciliationService reconciliationService;
    @Autowired FakeReportingSnapshotClient reportingSnapshotClient;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;

    private User sender;
    private User receiver;
    private User operator;
    private Account senderAccount;
    private Account receiverAccount;

    @BeforeEach
    void setUp() {
        reportingSnapshotClient.snapshots = List.of();
        String suffix = UUID.randomUUID().toString();

        sender = userRepository.save(User.builder()
                .fullName("Postgres Reconciliation Sender")
                .email("pg-recon-sender-" + suffix + "@test.local")
                .passwordHash("hashed")
                .role(Role.USER)
                .build());
        receiver = userRepository.save(User.builder()
                .fullName("Postgres Reconciliation Receiver")
                .email("pg-recon-receiver-" + suffix + "@test.local")
                .passwordHash("hashed")
                .role(Role.USER)
                .build());
        operator = userRepository.save(User.builder()
                .fullName("Postgres Reconciliation Operator")
                .email("pg-recon-operator-" + suffix + "@test.local")
                .passwordHash("hashed")
                .role(Role.OPERATOR)
                .build());

        senderAccount = accountRepository.save(account(sender, "PGS-" + suffix.substring(0, 12), new BigDecimal("1000000")));
        receiverAccount = accountRepository.save(account(receiver, "PGR-" + suffix.substring(0, 12), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("PostgreSQL reconciliation compares source and reporting after expected lag")
    void reconcile_postgresMatchingReporting_noExceptions() {
        OffsetDateTime completedAt = OffsetDateTime.now().minusMinutes(10);
        Transaction transaction = completedTransaction(completedAt, "250000");
        reportingSnapshotClient.snapshots = List.of(snapshot(transaction));

        var run = reconciliationService.reconcile(
                "vnd",
                completedAt.minusMinutes(1),
                completedAt.plusMinutes(1),
                operator.getEmail()
        );

        assertThat(run.status()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(run.sourceCount()).isEqualTo(1);
        assertThat(run.reportingCount()).isEqualTo(1);
        assertThat(run.exceptionCount()).isZero();
        assertThat(run.exceptions()).isEmpty();
    }

    @Test
    @DisplayName("PostgreSQL reconciliation rejects windows inside expected reporting lag")
    void reconcile_postgresWindowInsideExpectedLag_throwsException() {
        OffsetDateTime windowEnd = OffsetDateTime.now().minusMinutes(1);

        assertThatThrownBy(() -> reconciliationService.reconcile(
                "VND",
                windowEnd.minusHours(1),
                windowEnd,
                operator.getEmail()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowEnd must allow reporting lag");
    }

    private Account account(User user, String accountNumber, BigDecimal balance) {
        return Account.builder()
                .user(user)
                .accountNumber(accountNumber)
                .accountType(AccountType.PERSONAL)
                .balance(balance)
                .currency("VND")
                .status(AccountStatus.ACTIVE)
                .build();
    }

    private Transaction completedTransaction(OffsetDateTime completedAt, String amount) {
        Transaction transaction = Transaction.builder()
                .fromAccount(senderAccount)
                .toAccount(receiverAccount)
                .initiatedBy(sender)
                .amount(new BigDecimal(amount))
                .currency("VND")
                .idempotencyKey(UUID.randomUUID().toString())
                .status(TransactionStatus.PENDING)
                .build();
        transaction.markCompleted(completedAt);
        return transactionRepository.saveAndFlush(transaction);
    }

    private ReportingTransactionSnapshot snapshot(Transaction transaction) {
        return new ReportingTransactionSnapshot(
                transaction.getId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                transaction.getCompletedAt()
        );
    }

    @TestConfiguration
    static class FakeReportingConfig {
        @Bean
        @Primary
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
