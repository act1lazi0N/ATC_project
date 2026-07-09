package com.actilazion.aries_transaction.reconciliation.application;

import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationException;
import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationExceptionType;
import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationRun;
import com.actilazion.aries_transaction.reconciliation.dto.ReconciliationRunResponse;
import com.actilazion.aries_transaction.reconciliation.dto.ReportingTransactionSnapshot;
import com.actilazion.aries_transaction.reconciliation.infrastructure.ReconciliationRunRepository;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReconciliationServiceImpl implements ReconciliationService {
    private final TransactionRepository transactionRepository;
    private final ReconciliationRunRepository reconciliationRunRepository;
    private final ReportingTransactionSnapshotClient reportingSnapshotClient;

    @Override
    @Transactional
    public ReconciliationRunResponse reconcile(String currency, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        if (!windowStart.isBefore(windowEnd)) {
            throw new IllegalArgumentException("windowStart must be before windowEnd");
        }

        String normalizedCurrency = currency.toUpperCase();
        List<Transaction> sourceTransactions = transactionRepository.findForReconciliation(
                normalizedCurrency,
                windowStart,
                windowEnd
        );
        List<ReportingTransactionSnapshot> reportingSnapshots = reportingSnapshotClient.fetchSnapshots(
                normalizedCurrency,
                windowStart,
                windowEnd
        );

        ReconciliationRun run = ReconciliationRun.builder()
                .currency(normalizedCurrency)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .build();

        compareSourceAndReporting(run, sourceTransactions, reportingSnapshots);
        run.complete(sourceTransactions.size(), reportingSnapshots.size(), OffsetDateTime.now());

        return ReconciliationRunResponse.from(reconciliationRunRepository.save(run));
    }

    @Override
    @Transactional(readOnly = true)
    public ReconciliationRunResponse getRun(UUID runId) {
        ReconciliationRun run = reconciliationRunRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("ReconciliationRun", runId));
        return ReconciliationRunResponse.from(run);
    }

    private void compareSourceAndReporting(
            ReconciliationRun run,
            List<Transaction> sourceTransactions,
            List<ReportingTransactionSnapshot> reportingSnapshots
    ) {
        Map<UUID, Transaction> sourceById = sourceTransactions.stream()
                .collect(Collectors.toMap(Transaction::getId, Function.identity()));
        Map<UUID, List<ReportingTransactionSnapshot>> reportingByTransactionId = reportingSnapshots.stream()
                .collect(Collectors.groupingBy(ReportingTransactionSnapshot::transactionId));

        for (Transaction source : sourceTransactions) {
            List<ReportingTransactionSnapshot> matchingSnapshots = reportingByTransactionId.get(source.getId());
            if (matchingSnapshots == null || matchingSnapshots.isEmpty()) {
                run.addException(exception(
                        ReconciliationExceptionType.MISSING_IN_REPORTING,
                        source.getId(),
                        source.getAmount(),
                        null,
                        source.getStatus().name(),
                        null,
                        "Transaction is present in core but missing from reporting"
                ));
                continue;
            }

            if (matchingSnapshots.size() > 1) {
                run.addException(exception(
                        ReconciliationExceptionType.DUPLICATE_IN_REPORTING,
                        source.getId(),
                        source.getAmount(),
                        matchingSnapshots.getFirst().amount(),
                        source.getStatus().name(),
                        matchingSnapshots.getFirst().status().name(),
                        "Reporting contains " + matchingSnapshots.size() + " rows for one source transaction"
                ));
                continue;
            }

            compareSingleSnapshot(run, source, matchingSnapshots.getFirst());
        }

        for (ReportingTransactionSnapshot snapshot : reportingSnapshots) {
            if (!sourceById.containsKey(snapshot.transactionId())) {
                run.addException(exception(
                        ReconciliationExceptionType.UNEXPECTED_IN_REPORTING,
                        snapshot.transactionId(),
                        null,
                        snapshot.amount(),
                        null,
                        snapshot.status().name(),
                        "Reporting contains a transaction outside the core reconciliation source set"
                ));
            }
        }
    }

    private void compareSingleSnapshot(
            ReconciliationRun run,
            Transaction source,
            ReportingTransactionSnapshot snapshot
    ) {
        if (compareAmount(source.getAmount(), snapshot.amount()) != 0) {
            run.addException(exception(
                    ReconciliationExceptionType.AMOUNT_MISMATCH,
                    source.getId(),
                    source.getAmount(),
                    snapshot.amount(),
                    source.getStatus().name(),
                    snapshot.status().name(),
                    "Reporting amount does not match source transaction amount"
            ));
        }

        if (source.getStatus() != snapshot.status()) {
            run.addException(exception(
                    ReconciliationExceptionType.STATUS_MISMATCH,
                    source.getId(),
                    source.getAmount(),
                    snapshot.amount(),
                    source.getStatus().name(),
                    snapshot.status().name(),
                    "Reporting status does not match source transaction status"
            ));
        }
    }

    private int compareAmount(BigDecimal sourceAmount, BigDecimal reportingAmount) {
        if (sourceAmount == null && reportingAmount == null) {
            return 0;
        }
        if (sourceAmount == null) {
            return -1;
        }
        if (reportingAmount == null) {
            return 1;
        }
        return sourceAmount.compareTo(reportingAmount);
    }

    private ReconciliationException exception(
            ReconciliationExceptionType type,
            UUID transactionId,
            BigDecimal sourceAmount,
            BigDecimal reportingAmount,
            String sourceStatus,
            String reportingStatus,
            String details
    ) {
        return ReconciliationException.builder()
                .exceptionType(type)
                .transactionId(transactionId)
                .sourceAmount(sourceAmount)
                .reportingAmount(reportingAmount)
                .sourceStatus(sourceStatus)
                .reportingStatus(reportingStatus)
                .details(details)
                .build();
    }
}
