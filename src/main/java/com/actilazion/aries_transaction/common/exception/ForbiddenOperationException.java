package com.actilazion.aries_transaction.common.exception;

import org.springframework.http.HttpStatus;

public class ForbiddenOperationException extends AppException {
    public ForbiddenOperationException(String message) {
        super(message, HttpStatus.FORBIDDEN);
    }
}
