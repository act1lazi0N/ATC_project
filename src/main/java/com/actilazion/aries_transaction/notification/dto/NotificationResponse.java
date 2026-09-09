package com.actilazion.aries_transaction.notification.dto;

import com.actilazion.aries_transaction.notification.domain.Notification;
import com.actilazion.aries_transaction.notification.domain.NotificationType;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        NotificationType type,
        String title,
        String message,
        Map<String, Object> data,
        OffsetDateTime occurredAt,
        OffsetDateTime readAt,
        OffsetDateTime createdAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getPayload(),
                notification.getOccurredAt(),
                notification.getReadAt(),
                notification.getCreatedAt()
        );
    }
}
