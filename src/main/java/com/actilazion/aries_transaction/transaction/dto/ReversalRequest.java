package com.actilazion.aries_transaction.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReversalRequest(
        @NotBlank(message = "idempotencyKey is required")
        @Size(min = 16, max = 64, message = "idempotencyKey must be 16-64 characters")
        String idempotencyKey,

        @Size(max = 255, message = "description too long")
        String description
) {
}
