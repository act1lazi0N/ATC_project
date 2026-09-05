package com.actilazion.aries_transaction.notification.dto;

public record NotificationPreferenceResponse(
        boolean transactionEmailEnabled,
        boolean webhookAlertEmailEnabled,
        boolean emailVerified,
        long version
) {
}
