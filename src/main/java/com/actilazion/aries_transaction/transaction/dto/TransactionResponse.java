package com.actilazion.aries_transaction.transaction.dto;


import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.annotation.JsonInclude;

public record TransactionResponse(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal amount,
        String currency,
        TransactionStatus status,
        String idempotencyKey,
        String description,
        String failureReason,
        UUID originalTransactionId,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal refundedAmount,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) TransactionPartyView fromParty,
        @JsonInclude(JsonInclude.Include.NON_NULL) TransactionPartyView toParty,
        @JsonInclude(JsonInclude.Include.NON_NULL) TransactionDirection direction
) implements TransactionReadResponse {
    public TransactionResponse(UUID id, UUID fromAccountId, UUID toAccountId,
                               BigDecimal amount, String currency, TransactionStatus status,
                               String idempotencyKey, String description, String failureReason,
                               UUID originalTransactionId, BigDecimal refundedAmount,
                               OffsetDateTime createdAt, OffsetDateTime completedAt) {
        this(id, fromAccountId, toAccountId, amount, currency, status, idempotencyKey,
                description, failureReason, originalTransactionId, refundedAmount,
                createdAt, completedAt, null, null, null);
    }

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
                tx.getCompletedAt(),
                null,
                null,
                null
        );
    }

    public static TransactionResponse from(
            Transaction tx,
            TransactionPartyView fromParty,
            TransactionPartyView toParty,
            TransactionDirection direction
    ) {
        UUID fromAccountId = tx.getFromAccount() == null ? null : tx.getFromAccount().getId();
        UUID toAccountId = tx.getToAccount() == null ? null : tx.getToAccount().getId();
        return new TransactionResponse(
                tx.getId(),
                fromAccountId,
                toAccountId,
                tx.getAmount(),
                tx.getCurrency(),
                tx.getStatus(),
                tx.getIdempotencyKey(),
                tx.getDescription(),
                tx.getFailureReason(),
                tx.getOriginalTransaction() != null ? tx.getOriginalTransaction().getId() : null,
                tx.getRefundedAmount(),
                tx.getCreatedAt(),
                tx.getCompletedAt(),
                fromParty,
                toParty,
                direction
        );
    }
}
