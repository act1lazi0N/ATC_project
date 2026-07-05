package com.actilazion.aries_transaction.transaction.domain;

public enum IdempotencyRecordStatus {
    PROCESSING,
    COMPLETED,
    FAILED,
    EXPIRED
}
