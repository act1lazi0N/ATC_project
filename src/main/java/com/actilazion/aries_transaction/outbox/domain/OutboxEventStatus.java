package com.actilazion.aries_transaction.outbox.domain;

public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    FAILED
}
