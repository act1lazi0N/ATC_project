package com.actilazion.aries_transaction.operations.dto;

import java.util.List;
import java.util.UUID;

public record LedgerJournalResponse(
        UUID transactionId,
        List<LedgerEntryResponse> entries,
        String totalDebits,
        String totalCredits,
        boolean balanced
) {
}
