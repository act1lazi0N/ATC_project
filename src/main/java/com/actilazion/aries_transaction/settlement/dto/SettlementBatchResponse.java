package com.actilazion.aries_transaction.settlement.dto;

import com.actilazion.aries_transaction.settlement.domain.SettlementBatch;
import com.actilazion.aries_transaction.settlement.domain.SettlementBatchStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

public record SettlementBatchResponse(
        UUID id,
        String currency,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal grossAmount,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal feeAmount,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal netAmount,
        Integer feeRateBps,
        String idempotencyKey,
        OffsetDateTime cutoffCompletedAt,
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
                batch.getIdempotencyKey(),
                batch.getCutoffCompletedAt(),
                batch.getStatus(),
                batch.getCreatedAt(),
                batch.getItems().stream().map(SettlementItemResponse::from).toList()
        );
    }
}
