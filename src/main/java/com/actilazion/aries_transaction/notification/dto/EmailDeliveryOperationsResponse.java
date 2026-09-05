package com.actilazion.aries_transaction.notification.dto;

import com.actilazion.aries_transaction.notification.domain.EmailDelivery;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryPurpose;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EmailDeliveryOperationsResponse(
        UUID id,
        EmailDeliveryPurpose purpose,
        EmailDeliveryStatus status,
        int attemptCount,
        int cycleAttemptCount,
        int redriveCount,
        String lastErrorCode,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime deliveredAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static EmailDeliveryOperationsResponse from(EmailDelivery delivery) {
        return new EmailDeliveryOperationsResponse(
                delivery.getId(), delivery.getPurpose(), delivery.getStatus(),
                delivery.getAttemptCount(), delivery.getCycleAttemptCount(), delivery.getRedriveCount(),
                delivery.getLastErrorCode(), delivery.getNextAttemptAt(), delivery.getDeliveredAt(),
                delivery.getCreatedAt(), delivery.getUpdatedAt()
        );
    }
}
