package com.actilazion.aries_transaction.common.exception;

import org.springframework.security.access.AccessDeniedException;

public class ForbiddenOperationException extends AccessDeniedException {
    public ForbiddenOperationException(String message) {
        super(message);
    }
}
