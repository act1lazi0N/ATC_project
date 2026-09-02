package com.actilazion.aries_transaction.common.exception;

import org.springframework.http.HttpStatus;

public class CustomerVersionConflictException extends AppException {
    public CustomerVersionConflictException() {
        super("Customer status changed since it was loaded. Refresh before retrying.", HttpStatus.CONFLICT);
    }
}
