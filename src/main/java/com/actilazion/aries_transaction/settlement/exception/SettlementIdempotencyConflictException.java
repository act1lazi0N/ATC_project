package com.actilazion.aries_transaction.settlement.exception;

import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class SettlementIdempotencyConflictException extends AppException {
    public SettlementIdempotencyConflictException(String idempotencyKey) {
        super("Settlement idempotency key was reused with different request: " + idempotencyKey, HttpStatus.CONFLICT);
    }
}
