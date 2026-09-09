package com.actilazion.aries_transaction.notification.domain;

public enum EmailDeliveryStatus {
    PENDING,
    PROCESSING,
    DELIVERED,
    FAILED,
    DEAD_LETTERED,
    CANCELLED
}
