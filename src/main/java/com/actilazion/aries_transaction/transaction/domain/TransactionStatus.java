package com.actilazion.aries_transaction.transaction.domain;

public enum TransactionStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REVERSED,
    REFUNDED,
    PARTIALLY_REFUNDED;

    public boolean canTransitionTo(TransactionStatus target) {
        return TransactionStateGuard.canTransition(this, target);
    }
}
