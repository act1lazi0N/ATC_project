package com.actilazion.aries_transaction.reconciliation.dto;

import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ReportingTransactionSnapshot(
        UUID transactionId,
        BigDecimal amount,
        String currency,
        TransactionStatus status,
        OffsetDateTime completedAt
) {
}
