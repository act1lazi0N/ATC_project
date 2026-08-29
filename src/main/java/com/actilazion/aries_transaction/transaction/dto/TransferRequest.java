package com.actilazion.aries_transaction.transaction.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(
        @NotBlank(message = "fromAccountId is required")
        String fromAccountId,

        @NotBlank(message = "toAccountId is required")
        String toAccountId,

        @NotNull(message = "amount is required")
        BigDecimal amount,

        @NotBlank(message = "idempotencyKey is required")
        @Size(min = 16, max = 64, message = "idempotencyKey must be 16–64 characters")
        String idempotencyKey,

        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO code, e.g. VND")
        String currency,

        @Size(max = 255, message = "description too long")
        String description,

        UUID previewId
) {
    public TransferRequest(String fromAccountId, String toAccountId, BigDecimal amount,
                           String idempotencyKey, String currency, String description) {
        this(fromAccountId, toAccountId, amount, idempotencyKey, currency, description, null);
    }
}
