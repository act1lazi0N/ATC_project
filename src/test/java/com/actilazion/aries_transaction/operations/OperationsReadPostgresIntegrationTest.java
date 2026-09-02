package com.actilazion.aries_transaction.operations;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.audit.domain.IdentityAuditEventType;
import com.actilazion.aries_transaction.audit.infrastructure.IdentityAuditLogRepository;
import com.actilazion.aries_transaction.common.exception.CustomerVersionConflictException;
import com.actilazion.aries_transaction.identity.domain.RefreshSession;
import com.actilazion.aries_transaction.identity.domain.RefreshSessionRevocationReason;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.RefreshSessionRepository;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.ledger.domain.LedgerDirection;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntry;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntryType;
import com.actilazion.aries_transaction.ledger.infrastructure.LedgerEntryRepository;
import com.actilazion.aries_transaction.operations.application.CustomerOperationsService;
import com.actilazion.aries_transaction.operations.application.OperationsLedgerService;
import com.actilazion.aries_transaction.operations.dto.CustomerStatus;
import com.actilazion.aries_transaction.operations.dto.UpdateCustomerStatusRequest;
import com.actilazion.aries_transaction.overview.application.MerchantOverviewService;
import com.actilazion.aries_transaction.support.PostgresIntegrationTestSupport;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionOperation;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class OperationsReadPostgresIntegrationTest extends PostgresIntegrationTestSupport {
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired RefreshSessionRepository refreshSessionRepository;
    @Autowired IdentityAuditLogRepository identityAuditLogRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CustomerOperationsService customerOperationsService;
    @Autowired OperationsLedgerService operationsLedgerService;
    @Autowired MerchantOverviewService merchantOverviewService;

    private User operator;
    private User merchant;
    private User counterparty;
    private Account merchantAccount;
    private Account counterpartyAccount;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        operator = userRepository.saveAndFlush(user("Operations Staff", "operator-" + suffix + "@test.local", Role.OPERATOR));
        merchant = userRepository.saveAndFlush(user("Merchant Customer", "merchant-" + suffix + "@test.local", Role.MERCHANT));
        counterparty = userRepository.saveAndFlush(user("Customer Counterparty", "customer-" + suffix + "@test.local", Role.USER));
        merchantAccount = accountRepository.saveAndFlush(account(merchant, "91" + digits(suffix), new BigDecimal("1250000.25")));
        counterpartyAccount = accountRepository.saveAndFlush(account(counterparty, "92" + digits(UUID.randomUUID().toString()), new BigDecimal("9000.00")));
    }

    @Test
    void suspendRevokesSessionsAuditsActorAndPreservesFinancialState() {
        long expectedVersion = merchant.getVersion();
        BigDecimal balanceBefore = merchantAccount.getBalance();
        RefreshSession session = refreshSessionRepository.saveAndFlush(RefreshSession.builder()
                .user(merchant)
                .refreshTokenHash(UUID.randomUUID().toString().replace("-", "").repeat(2))
                .expiresAt(OffsetDateTime.now().plusDays(7))
                .familyId(UUID.randomUUID())
                .build());

        var result = customerOperationsService.updateStatus(
                operator.getId(), merchant.getId(),
                new UpdateCustomerStatusRequest(CustomerStatus.SUSPENDED, "Confirmed customer access review", expectedVersion),
                "127.0.0.1");

        assertThat(result.status()).isEqualTo(CustomerStatus.SUSPENDED);
        assertThat(result.version()).isGreaterThan(expectedVersion);
        assertThat(userRepository.findById(merchant.getId()).orElseThrow().getIsActive()).isFalse();
        assertThat(accountRepository.findById(merchantAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(balanceBefore);
        assertThat(refreshSessionRepository.findById(session.getId()).orElseThrow())
                .extracting(RefreshSession::getRevokedReason)
                .isEqualTo(RefreshSessionRevocationReason.ADMIN_REVOKED);
        assertThat(identityAuditLogRepository.findAllByUserIdOrderByCreatedAtAsc(merchant.getId()))
                .anySatisfy(log -> {
                    assertThat(log.getEventType()).isEqualTo(IdentityAuditEventType.CUSTOMER_SUSPENDED);
                    assertThat(log.getActorUserId()).isEqualTo(operator.getId());
                    assertThat(log.getMetadata()).containsEntry("reason", "Confirmed customer access review");
                    assertThat(log.getIdentityHash()).isNull();
                });

        assertThatThrownBy(() -> customerOperationsService.updateStatus(
                operator.getId(), merchant.getId(),
                new UpdateCustomerStatusRequest(CustomerStatus.ACTIVE, "Stale administrative retry", expectedVersion),
                "127.0.0.1"))
                .isInstanceOf(CustomerVersionConflictException.class);
    }

    @Test
    void customerOperationsRejectStaffTargetsAndReturnMaskedAccountProjection() {
        var detail = customerOperationsService.getCustomer(operator.getId(), merchant.getId());
        assertThat(detail.accounts()).singleElement().satisfies(account -> {
            assertThat(account.maskedAccountNumber()).endsWith(merchantAccount.getAccountNumber().substring(merchantAccount.getAccountNumber().length() - 4));
            assertThat(account.maskedAccountNumber()).doesNotContain(merchantAccount.getAccountNumber());
        });

        assertThatThrownBy(() -> customerOperationsService.getCustomer(operator.getId(), operator.getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> customerOperationsService.updateStatus(
                operator.getId(), operator.getId(),
                new UpdateCustomerStatusRequest(CustomerStatus.SUSPENDED, "Invalid self target", operator.getVersion()),
                "127.0.0.1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ledgerUsesStableCursorExactStringsMaskedAccountsAndBalancedJournals() {
        Transaction first = transactionRepository.saveAndFlush(transaction("ledger-one-" + UUID.randomUUID(), new BigDecimal("1234567890123456.78")));
        ledgerEntryRepository.saveAndFlush(leg(first, merchantAccount, LedgerDirection.DEBIT, first.getAmount()));
        ledgerEntryRepository.saveAndFlush(leg(first, counterpartyAccount, LedgerDirection.CREDIT, first.getAmount()));
        Transaction second = transactionRepository.saveAndFlush(transaction("ledger-two-" + UUID.randomUUID(), new BigDecimal("0.01")));
        ledgerEntryRepository.saveAndFlush(leg(second, merchantAccount, LedgerDirection.DEBIT, second.getAmount()));
        ledgerEntryRepository.saveAndFlush(leg(second, counterpartyAccount, LedgerDirection.CREDIT, second.getAmount()));

        var pageOne = operationsLedgerService.findEntries(operator.getId(), null, null, null, null, null, "VND", null, 2);
        assertThat(pageOne.content()).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.maskedAccountReference()).startsWith("*");
            assertThat(entry.balanced()).isTrue();
        });
        assertThat(pageOne.hasMore()).isTrue();
        assertThat(pageOne.nextCursor()).isNotBlank();
        var pageTwo = operationsLedgerService.findEntries(operator.getId(), null, null, null, null, null, "VND", pageOne.nextCursor(), 2);
        assertThat(pageTwo.content()).extracting(entry -> entry.entryId())
                .doesNotContainAnyElementsOf(pageOne.content().stream().map(entry -> entry.entryId()).toList());

        var journal = operationsLedgerService.getJournal(operator.getId(), first.getId());
        assertThat(journal.balanced()).isTrue();
        assertThat(journal.totalDebits()).isEqualTo("1234567890123456.78");
        assertThat(journal.totalCredits()).isEqualTo("1234567890123456.78");
    }

    @Test
    void merchantOverviewKeepsCurrenciesSeparateAndReportsConfirmedVersusPending() {
        transactionRepository.saveAndFlush(transaction("merchant-completed-" + UUID.randomUUID(), new BigDecimal("500.25")));
        Transaction pending = Transaction.builder()
                .fromAccount(merchantAccount).toAccount(counterpartyAccount).initiatedBy(merchant)
                .amount(new BigDecimal("75.10")).currency("VND").status(TransactionStatus.PENDING)
                .operation(TransactionOperation.TRANSFER)
                .idempotencyKey(("merchant-pending-" + UUID.randomUUID()).substring(0, 53))
                .refundedAmount(BigDecimal.ZERO).build();
        transactionRepository.saveAndFlush(pending);

        var overview = merchantOverviewService.getOverview(merchant.getId(), "7d", "Asia/Ho_Chi_Minh");
        assertThat(overview.currencies()).singleElement().satisfies(bucket -> {
            assertThat(bucket.currency()).isEqualTo("VND");
            assertThat(bucket.balance()).isEqualTo("1250000.25");
            assertThat(bucket.outflow()).isEqualTo("500.25");
            assertThat(bucket.pending()).isEqualTo("75.10");
            assertThat(bucket.pendingCount()).isEqualTo(1);
            assertThat(bucket.trend()).hasSize(7);
        });
    }

    @Test
    void postgresPlansUseCustomerAndLedgerReadIndexes() {
        assertThat(explain("""
                SELECT id
                FROM users
                WHERE role = 'MERCHANT' AND is_active = true
                ORDER BY created_at DESC, id DESC
                LIMIT 20
                """)).contains("idx_users_customer_search");
        assertThat(explain("""
                SELECT id
                FROM ledger_entries
                WHERE currency = 'VND' AND entry_type = 'TRANSFER' AND direction = 'DEBIT'
                ORDER BY created_at DESC, id DESC
                LIMIT 50
                """)).contains("idx_ledger_entries_forensic_filters");
    }

    private String explain(String query) {
        return jdbcTemplate.execute((ConnectionCallback<String>) connection -> {
            List<String> plan = new ArrayList<>();
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                try (ResultSet rows = statement.executeQuery("EXPLAIN (COSTS OFF) " + query)) {
                    while (rows.next()) plan.add(rows.getString(1));
                } finally {
                    statement.execute("RESET enable_seqscan");
                }
            }
            return String.join("\n", plan);
        });
    }

    private User user(String name, String email, Role role) {
        return User.builder().fullName(name).email(email).passwordHash("hashed").role(role).build();
    }

    private Account account(User owner, String number, BigDecimal balance) {
        return Account.builder().user(owner).accountNumber(number.substring(0, Math.min(number.length(), 20)))
                .accountType(owner.getRole() == Role.MERCHANT ? AccountType.BUSINESS : AccountType.PERSONAL)
                .balance(balance).currency("VND").status(AccountStatus.ACTIVE).build();
    }

    private Transaction transaction(String key, BigDecimal amount) {
        return Transaction.builder().fromAccount(merchantAccount).toAccount(counterpartyAccount).initiatedBy(merchant)
                .amount(amount).currency("VND").status(TransactionStatus.COMPLETED)
                .operation(TransactionOperation.TRANSFER).idempotencyKey(key.substring(0, Math.min(key.length(), 64)))
                .refundedAmount(BigDecimal.ZERO).completedAt(OffsetDateTime.now()).build();
    }

    private LedgerEntry leg(Transaction transaction, Account account, LedgerDirection direction, BigDecimal amount) {
        return LedgerEntry.builder().transactionId(transaction.getId()).accountId(account.getId()).direction(direction)
                .amount(amount).currency("VND").entryType(LedgerEntryType.TRANSFER).build();
    }

    private String digits(String value) {
        String digits = Integer.toUnsignedString(value.hashCode());
        return (digits + "000000000000000000").substring(0, 16);
    }
}
