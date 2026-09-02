package com.actilazion.aries_transaction.operations.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateCustomerStatusRequest(
        @NotNull CustomerStatus status,
        @NotBlank @Size(max = 500) String reason,
        @Min(0) long expectedVersion
) {
}
