package com.actilazion.aries_transaction.settlement.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record CreateSettlementBatchRequest(
        @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be 3 characters")
        String currency,

        @NotNull(message = "feeRateBps is required")
        @Min(value = 0, message = "feeRateBps must be non-negative")
        @Max(value = 10000, message = "feeRateBps cannot exceed 10000")
        Integer feeRateBps,

        @NotBlank(message = "idempotencyKey is required")
        @Size(max = 64, message = "idempotencyKey cannot exceed 64 characters")
        String idempotencyKey,

        @NotNull(message = "cutoffCompletedAt is required")
        OffsetDateTime cutoffCompletedAt
) {
}
