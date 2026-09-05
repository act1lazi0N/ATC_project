package com.actilazion.aries_transaction.webhook.domain;

public enum WebhookDeliveryAttemptOutcome {
    DELIVERED,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE,
    CANCELLED
}
