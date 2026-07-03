package com.actilazion.aries_transaction.transaction.domain;

public enum TransactionStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REVERSED,
    REFUNDED,
    PARTIALLY_REFUNDED;

    public boolean canTransitionTo(TransactionStatus target) {
        if (this == target) {
            return true;
        }

        return switch (this) {
            case PENDING -> target == COMPLETED || target == FAILED;
            case COMPLETED -> target == REVERSED || target == REFUNDED || target == PARTIALLY_REFUNDED;
            case PARTIALLY_REFUNDED -> target == REFUNDED;
            case FAILED, REVERSED, REFUNDED -> false;
        };
    }
}
