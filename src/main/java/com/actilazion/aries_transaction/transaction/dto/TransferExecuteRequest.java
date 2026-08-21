package com.actilazion.aries_transaction.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;

public record TransferExecuteRequest(
        UUID previewId,
        @NotBlank @Size(min = 16, max = 64) String idempotencyKey,
        String fromAccountId,
        String toAccountId,
        BigDecimal amount,
        @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @Size(max = 255) String description
) {
    public TransferExecuteRequest(UUID previewId, String idempotencyKey) {
        this(previewId, idempotencyKey, null, null, null, null, null);
    }
}
