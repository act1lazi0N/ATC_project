package com.actilazion.aries_transaction.operations.dto;

import com.actilazion.aries_transaction.ledger.domain.LedgerDirection;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntryType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID entryId,
        UUID transactionId,
        String maskedAccountReference,
        LedgerDirection direction,
        String amount,
        String currency,
        LedgerEntryType entryType,
        OffsetDateTime createdAt,
        boolean balanced
) {
}
