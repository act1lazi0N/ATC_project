package com.actilazion.aries_transaction.overview.application;

import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.ledger.domain.LedgerDirection;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntry;
import com.actilazion.aries_transaction.ledger.infrastructure.LedgerEntryRepository;
import com.actilazion.aries_transaction.overview.dto.OperationsOverviewResponse;
import com.actilazion.aries_transaction.reconciliation.infrastructure.ReconciliationRunRepository;
import com.actilazion.aries_transaction.settlement.domain.SettlementBatchStatus;
import com.actilazion.aries_transaction.settlement.infrastructure.SettlementBatchRepository;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OperationsOverviewService {
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final ReconciliationRunRepository reconciliationRunRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    @Transactional(readOnly = true)
    public OperationsOverviewResponse getOverview(UUID actorId, String range) {
        requireStaff(actorId);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime from = switch (range) {
            case "24h" -> now.minusHours(24);
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            default -> throw new IllegalArgumentException("range must be 24h, 7d, or 30d");
        };

        long users = countCustomers(Role.USER, null);
        long merchants = countCustomers(Role.MERCHANT, null);
        long active = countCustomers(null, true);
        long suspended = countCustomers(null, false);

        var transactions = transactionRepository.findAllByCreatedAtGreaterThanEqual(from);
        var runs = reconciliationRunRepository.findAllByCreatedAtGreaterThanEqual(from);
        var batches = settlementBatchRepository.findAllByCreatedAtGreaterThanEqual(from);
        var entries = ledgerEntryRepository.findAllByCreatedAtGreaterThanEqual(from);
        Map<UUID, List<LedgerEntry>> journals = entries.stream()
                .collect(Collectors.groupingBy(LedgerEntry::getTransactionId));
        long unbalanced = journals.values().stream().filter(legs -> !balanced(legs)).count();

        return new OperationsOverviewResponse(
                range,
                now,
                new OperationsOverviewResponse.CustomerHealth(users, merchants, active, suspended),
                new OperationsOverviewResponse.TransactionHealth(
                        transactions.size(),
                        transactions.stream().filter(tx -> tx.getStatus() == TransactionStatus.PENDING).count(),
                        transactions.stream().filter(tx -> tx.getStatus() == TransactionStatus.FAILED).count()),
                new OperationsOverviewResponse.ReconciliationHealth(
                        runs.size(), runs.stream().mapToLong(run -> run.getExceptionCount()).sum()),
                new OperationsOverviewResponse.SettlementHealth(
                        batches.size(),
                        batches.stream().filter(batch -> batch.getStatus() == SettlementBatchStatus.PENDING
                                || batch.getStatus() == SettlementBatchStatus.PROCESSING).count(),
                        batches.stream().filter(batch -> batch.getStatus() == SettlementBatchStatus.FAILED).count()),
                new OperationsOverviewResponse.LedgerHealth(entries.size(), journals.size(), unbalanced, unbalanced == 0)
        );
    }

    private long countCustomers(Role role, Boolean active) {
        return userRepository.count((root, query, builder) -> {
            var predicate = root.get("role").in(Role.USER, Role.MERCHANT);
            if (role != null) predicate = builder.and(predicate, builder.equal(root.get("role"), role));
            if (active != null) predicate = builder.and(predicate, builder.equal(root.get("isActive"), active));
            return predicate;
        });
    }

    private boolean balanced(List<LedgerEntry> entries) {
        BigDecimal debits = entries.stream().filter(entry -> entry.getDirection() == LedgerDirection.DEBIT)
                .map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = entries.stream().filter(entry -> entry.getDirection() == LedgerDirection.CREDIT)
                .map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return debits.compareTo(credits) == 0;
    }

    private User requireStaff(UUID actorId) {
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("User", actorId));
        if (actor.getRole() != Role.OPERATOR && actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Caller is not authorized for operations overview");
        }
        return actor;
    }
}
