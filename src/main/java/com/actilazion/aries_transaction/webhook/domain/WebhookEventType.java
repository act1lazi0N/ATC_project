package com.actilazion.aries_transaction.webhook.domain;

import com.actilazion.aries_transaction.transaction.domain.TransactionOperation;

import java.util.Arrays;
import java.util.Optional;

public enum WebhookEventType {
    TRANSFER_COMPLETED("TransferCompleted", "transaction.transfer.completed.v1", TransactionOperation.TRANSFER),
    REVERSAL_COMPLETED("ReversalCompleted", "transaction.reversal.completed.v1", TransactionOperation.REVERSAL),
    REFUND_COMPLETED("RefundCompleted", "transaction.refund.completed.v1", TransactionOperation.REFUND);

    private final String outboxEventType;
    private final String publicType;
    private final TransactionOperation operation;

    WebhookEventType(String outboxEventType, String publicType, TransactionOperation operation) {
        this.outboxEventType = outboxEventType;
        this.publicType = publicType;
        this.operation = operation;
    }

    public String outboxEventType() {
        return outboxEventType;
    }

    public String publicType() {
        return publicType;
    }

    public TransactionOperation operation() {
        return operation;
    }

    public static Optional<WebhookEventType> fromOutboxEventType(String value) {
        return Arrays.stream(values())
                .filter(type -> type.outboxEventType.equals(value))
                .findFirst();
    }
}
