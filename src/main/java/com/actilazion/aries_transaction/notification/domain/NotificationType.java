package com.actilazion.aries_transaction.notification.domain;

public enum NotificationType {
    TRANSFER_COMPLETED(NotificationCategory.TRANSACTION),
    REVERSAL_COMPLETED(NotificationCategory.TRANSACTION),
    REFUND_COMPLETED(NotificationCategory.TRANSACTION),
    WEBHOOK_ENDPOINT_DISABLED(NotificationCategory.WEBHOOK_ALERT),
    WEBHOOK_DELIVERY_DEAD_LETTERED(NotificationCategory.WEBHOOK_ALERT);

    private final NotificationCategory category;

    NotificationType(NotificationCategory category) {
        this.category = category;
    }

    public NotificationCategory category() {
        return category;
    }
}
