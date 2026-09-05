package com.actilazion.aries_transaction.common.exception;

import org.springframework.http.HttpStatus;

public class NotificationPreferenceVersionConflictException extends AppException {
    public NotificationPreferenceVersionConflictException() {
        super("Notification preferences changed; refresh and retry", HttpStatus.CONFLICT);
    }
}
