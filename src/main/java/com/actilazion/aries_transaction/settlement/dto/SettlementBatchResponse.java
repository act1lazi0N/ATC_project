package com.actilazion.aries_transaction.settlement.dto;

import com.actilazion.aries_transaction.settlement.domain.SettlementBatch;
import com.actilazion.aries_transaction.settlement.domain.SettlementBatchStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SettlementBatchResponse(
        UUID id,
        String currency,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        Integer feeRateBps,
        SettlementBatchStatus status,
        OffsetDateTime createdAt,
        List<SettlementItemResponse> items
) {
    public static SettlementBatchResponse from(SettlementBatch batch) {
        return new SettlementBatchResponse(
                batch.getId(),
                batch.getCurrency(),
                batch.getGrossAmount(),
                batch.getFeeAmount(),
                batch.getNetAmount(),
                batch.getFeeRateBps(),
                batch.getStatus(),
                batch.getCreatedAt(),
                batch.getItems().stream().map(SettlementItemResponse::from).toList()
        );
    }
}
