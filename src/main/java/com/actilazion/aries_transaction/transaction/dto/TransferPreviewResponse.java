package com.actilazion.aries_transaction.transaction.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TransferPreviewResponse(
        UUID previewId,
        OffsetDateTime expiresAt,
        MaskedAccount source,
        MaskedAccount recipient,
        String amount,
        String fee,
        String debitTotal,
        String currency,
        List<String> warnings
) {
    public record MaskedAccount(String accountNumberMasked, String displayName) {}
}
