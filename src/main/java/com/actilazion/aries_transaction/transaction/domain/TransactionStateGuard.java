package com.actilazion.aries_transaction.transaction.domain;

import com.actilazion.aries_transaction.transaction.domain.exception.InvalidTransactionStateTransitionException;

import java.util.Objects;

public final class TransactionStateGuard {
    private TransactionStateGuard() {
    }

    public static boolean canTransition(TransactionStatus current, TransactionStatus target) {
        Objects.requireNonNull(current, "Current transaction status must not be null");
        Objects.requireNonNull(target, "Target transaction status must not be null");

        return switch (current) {
            case PENDING -> target == TransactionStatus.COMPLETED || target == TransactionStatus.FAILED;
            case COMPLETED -> target == TransactionStatus.REVERSED
                    || target == TransactionStatus.REFUNDED
                    || target == TransactionStatus.PARTIALLY_REFUNDED;
            case PARTIALLY_REFUNDED -> target == TransactionStatus.REFUNDED;
            case FAILED, REVERSED, REFUNDED -> false;
        };
    }

    public static void assertCanTransition(TransactionStatus current, TransactionStatus target) {
        if (!canTransition(current, target)) {
            throw new InvalidTransactionStateTransitionException(current, target);
        }
    }

    public static void assertCanReverse(Transaction transaction) {
        TransactionStatus status = transaction.getStatus();
        if (status != TransactionStatus.COMPLETED) {
            throw new InvalidTransactionStateTransitionException(status, TransactionStatus.REVERSED);
        }
    }

    public static void assertCanRefund(Transaction transaction) {
        TransactionStatus status = transaction.getStatus();
        if (status != TransactionStatus.COMPLETED && status != TransactionStatus.PARTIALLY_REFUNDED) {
            throw new InvalidTransactionStateTransitionException(status, TransactionStatus.REFUNDED);
        }
    }
}
