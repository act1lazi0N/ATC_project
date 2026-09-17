package com.actilazion.aries_transaction.transaction.dto;

import com.actilazion.aries_transaction.transaction.domain.TransferPreviewMode;
import jakarta.validation.constraints.*;

import java.util.UUID;

public record TransferPreviewRequest(
        TransferPreviewMode mode,
        @NotNull UUID sourceAccountId,
        UUID toAccountId,
        String recipientAccountNumber,
        @Size(max = 64) @Pattern(regexp = "^\\d{1,16}(\\.\\d+)?$", message = "amount must be a decimal string") String amount,
        @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @Size(max = 255) String description,
        UUID qrCodeId
) {
    public TransferPreviewRequest(TransferPreviewMode mode, UUID sourceAccountId, UUID toAccountId,
                                  String recipientAccountNumber, String amount, String currency, String description) {
        this(mode, sourceAccountId, toAccountId, recipientAccountNumber, amount, currency, description, null);
    }
}
