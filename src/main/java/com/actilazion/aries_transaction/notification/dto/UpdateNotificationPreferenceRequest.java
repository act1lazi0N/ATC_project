package com.actilazion.aries_transaction.notification.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateNotificationPreferenceRequest(
        @NotNull Boolean transactionEmailEnabled,
        @NotNull Boolean webhookAlertEmailEnabled,
        @Min(0) long expectedVersion
) {
}
