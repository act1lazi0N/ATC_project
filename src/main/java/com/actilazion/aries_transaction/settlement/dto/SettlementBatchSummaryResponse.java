package com.actilazion.aries_transaction.settlement.dto;

import com.actilazion.aries_transaction.settlement.domain.SettlementBatch;
import com.actilazion.aries_transaction.settlement.domain.SettlementBatchStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SettlementBatchSummaryResponse(
        UUID id,
        String currency,
        String grossAmount,
        String feeAmount,
        String netAmount,
        int feeRateBps,
        OffsetDateTime cutoffCompletedAt,
        SettlementBatchStatus status,
        OffsetDateTime createdAt
) {
    public static SettlementBatchSummaryResponse from(SettlementBatch batch) {
        return new SettlementBatchSummaryResponse(
                batch.getId(),
                batch.getCurrency(),
                batch.getGrossAmount().toPlainString(),
                batch.getFeeAmount().toPlainString(),
                batch.getNetAmount().toPlainString(),
                batch.getFeeRateBps(),
                batch.getCutoffCompletedAt(),
                batch.getStatus(),
                batch.getCreatedAt()
        );
    }
}
