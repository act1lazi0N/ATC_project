package com.actilazion.aries_transaction.notification.domain;

public enum EmailDeliveryAttemptOutcome {
    DELIVERED,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE,
    CANCELLED
}
