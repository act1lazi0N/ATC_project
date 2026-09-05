package com.actilazion.aries_transaction.webhook.domain;

public enum WebhookDeliveryStatus {
    PENDING,
    PROCESSING,
    DELIVERED,
    FAILED,
    DEAD_LETTERED,
    CANCELLED
}
