package com.actilazion.aries_transaction.reconciliation.dto;

import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationRun;
import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationRunStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ReconciliationRunResponse(
        UUID id,
        String currency,
        OffsetDateTime windowStart,
        OffsetDateTime windowEnd,
        ReconciliationRunStatus status,
        int sourceCount,
        int reportingCount,
        int exceptionCount,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        List<ReconciliationExceptionResponse> exceptions
) {
    public static ReconciliationRunResponse from(ReconciliationRun run) {
        return new ReconciliationRunResponse(
                run.getId(),
                run.getCurrency(),
                run.getWindowStart(),
                run.getWindowEnd(),
                run.getStatus(),
                run.getSourceCount(),
                run.getReportingCount(),
                run.getExceptionCount(),
                run.getCreatedAt(),
                run.getCompletedAt(),
                run.getExceptions().stream().map(ReconciliationExceptionResponse::from).toList()
        );
    }
}
