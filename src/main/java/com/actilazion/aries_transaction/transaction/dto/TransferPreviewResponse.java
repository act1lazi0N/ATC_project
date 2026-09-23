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
        List<String> warnings,
        String authorizationRequirement,
        String enrollmentState
) {
    public TransferPreviewResponse(UUID previewId, OffsetDateTime expiresAt, MaskedAccount source,
            MaskedAccount recipient, String amount, String fee, String debitTotal, String currency, List<String> warnings) {
        this(previewId, expiresAt, source, recipient, amount, fee, debitTotal, currency, warnings, "NONE", "UNAVAILABLE");
    }
    public record MaskedAccount(String accountNumberMasked, String displayName) {}
}
