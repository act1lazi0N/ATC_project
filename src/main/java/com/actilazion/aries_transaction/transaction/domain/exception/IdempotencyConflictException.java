package com.actilazion.aries_transaction.transaction.domain.exception;


import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class IdempotencyConflictException extends AppException {
    public IdempotencyConflictException(String idempotencyKey) {
        super(
                "Idempotency key '" + idempotencyKey + "' was already used for a different transfer request.",
                HttpStatus.CONFLICT
        );
    }
}
