package com.actilazion.aries_transaction.exception;

import org.springframework.http.HttpStatus;

public class DuplicateTransferException extends AppException {
    public DuplicateTransferException(String idempotencyKey) {
        super(
                "Transaction with idempotency key '" + idempotencyKey + "' already exists.",
                HttpStatus.CONFLICT
        );
    }
}
