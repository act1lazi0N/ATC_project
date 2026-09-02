package com.actilazion.aries_transaction.reconciliation.dto;

import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationRun;
import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationRunStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReconciliationRunSummaryResponse(
        UUID id,
        String currency,
        OffsetDateTime windowStart,
        OffsetDateTime windowEnd,
        ReconciliationRunStatus status,
        int sourceCount,
        int reportingCount,
        int exceptionCount,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {
    public static ReconciliationRunSummaryResponse from(ReconciliationRun run) {
        return new ReconciliationRunSummaryResponse(
                run.getId(), run.getCurrency(), run.getWindowStart(), run.getWindowEnd(), run.getStatus(),
                run.getSourceCount(), run.getReportingCount(), run.getExceptionCount(),
                run.getCreatedAt(), run.getCompletedAt()
        );
    }
}
