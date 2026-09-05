package com.actilazion.aries_transaction.common.exception;

import org.springframework.http.HttpStatus;

public class EmailDeliveryRedriveConflictException extends AppException {
    public EmailDeliveryRedriveConflictException() {
        super("Only dead-lettered email deliveries can be retried", HttpStatus.CONFLICT);
    }
}
