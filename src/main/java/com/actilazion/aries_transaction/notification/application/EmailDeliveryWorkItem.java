package com.actilazion.aries_transaction.notification.application;

import com.actilazion.aries_transaction.notification.infrastructure.email.EmailMessage;

import java.util.UUID;

public record EmailDeliveryWorkItem(
        UUID deliveryId,
        UUID claimToken,
        int attemptNumber,
        EmailMessage message
) {
}
