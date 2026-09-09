package com.actilazion.aries_transaction.notification.application;

import com.actilazion.aries_transaction.notification.domain.NotificationType;

import java.time.OffsetDateTime;
import java.util.Map;

public record NotificationTransactionProjection(
        NotificationType type,
        String title,
        String message,
        Map<String, Object> payload,
        OffsetDateTime occurredAt
) {
}
