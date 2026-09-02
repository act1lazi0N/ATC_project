package com.actilazion.aries_transaction.overview.dto;

import java.time.OffsetDateTime;

public record OperationsOverviewResponse(
        String range,
        OffsetDateTime generatedAt,
        CustomerHealth customers,
        TransactionHealth transactions,
        ReconciliationHealth reconciliation,
        SettlementHealth settlements,
        LedgerHealth ledger
) {
    public record CustomerHealth(long users, long merchants, long active, long suspended) {}

    public record TransactionHealth(long total, long pending, long failed) {}

    public record ReconciliationHealth(long runs, long exceptions) {}

    public record SettlementHealth(long batches, long pending, long failed) {}

    public record LedgerHealth(long entries, long journals, long unbalancedJournals, boolean healthy) {}
}
