package com.actilazion.aries_transaction.reconciliation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record CreateReconciliationRunRequest(
        @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be 3 characters")
        String currency,

        @NotNull(message = "windowStart is required")
        OffsetDateTime windowStart,

        @NotNull(message = "windowEnd is required")
        OffsetDateTime windowEnd
) {
}
