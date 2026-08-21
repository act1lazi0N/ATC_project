package com.actilazion.aries_transaction.transaction.dto;

import com.actilazion.aries_transaction.transaction.domain.TransferPreviewMode;
import jakarta.validation.constraints.*;

import java.util.UUID;

public record TransferPreviewRequest(
        @NotNull TransferPreviewMode mode,
        @NotNull UUID sourceAccountId,
        UUID toAccountId,
        String recipientAccountNumber,
        @NotBlank @Pattern(regexp = "^\\d{1,16}(\\.\\d{1,2})?$", message = "amount must be a decimal string") String amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @Size(max = 255) String description
) {}
