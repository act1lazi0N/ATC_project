package com.actilazion.aries_transaction.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record TransferExecuteRequest(
        @NotNull UUID previewId,
        @NotBlank @Size(min = 16, max = 64) String idempotencyKey
) {}
