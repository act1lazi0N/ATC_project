package com.actilazion.aries_transaction.transaction.dto;


import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency,
        TransactionStatus status,
        String idempotencyKey,
        String description,
        String failureReason,
        UUID originalTransactionId,
        BigDecimal refundedAmount,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {
    public static TransactionResponse from(Transaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getFromAccount().getId(),
                tx.getToAccount().getId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getStatus(),
                tx.getIdempotencyKey(),
                tx.getDescription(),
                tx.getFailureReason(),
                tx.getOriginalTransaction() != null ? tx.getOriginalTransaction().getId() : null,
                tx.getRefundedAmount(),
                tx.getCreatedAt(),
                tx.getCompletedAt()
        );
    }
}
