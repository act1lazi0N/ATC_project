package com.actilazion.aries_transaction.outbox.domain;

public enum OutboxEventType {
    TRANSFER_COMPLETED("Transaction", "TransferCompleted"),
    REVERSAL_COMPLETED("Transaction", "ReversalCompleted"),
    REFUND_COMPLETED("Transaction", "RefundCompleted");

    private final String aggregateType;
    private final String eventType;

    OutboxEventType(String aggregateType, String eventType) {
        this.aggregateType = aggregateType;
        this.eventType = eventType;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public String eventType() {
        return eventType;
    }
}
