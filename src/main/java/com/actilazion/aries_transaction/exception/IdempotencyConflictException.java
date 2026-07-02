package com.actilazion.aries_transaction.exception;

import org.springframework.http.HttpStatus;

public class IdempotencyConflictException extends AppException {
    public IdempotencyConflictException(String idempotencyKey) {
        super(
                "Idempotency key '" + idempotencyKey + "' was already used for a different transfer request.",
                HttpStatus.CONFLICT
        );
    }
}
