package com.actilazion.aries_transaction.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RefundRequest(
        @NotNull(message = "amount is required")
        @DecimalMin(value = "1000", message = "Minimum refund amount is 1000")
        @Digits(integer = 16, fraction = 2, message = "Invalid amount format")
        BigDecimal amount,

        @NotBlank(message = "idempotencyKey is required")
        @Size(min = 16, max = 64, message = "idempotencyKey must be 16-64 characters")
        String idempotencyKey,

        @Size(max = 255, message = "description too long")
        String description
) {
}
