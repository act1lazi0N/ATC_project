package com.actilazion.aries_transaction.transaction.domain.exception;


import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class DuplicateTransferException extends AppException {
    public DuplicateTransferException(String idempotencyKey) {
        super(
                "Transaction with idempotency key '" + idempotencyKey + "' already exists.",
                HttpStatus.CONFLICT
        );
    }
}
