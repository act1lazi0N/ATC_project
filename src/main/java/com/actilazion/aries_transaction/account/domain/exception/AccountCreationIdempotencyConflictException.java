package com.actilazion.aries_transaction.account.domain.exception;

import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class AccountCreationIdempotencyConflictException extends AppException {
    public AccountCreationIdempotencyConflictException() {
        super("Idempotency key was already used for a different account request", HttpStatus.CONFLICT);
    }
}
