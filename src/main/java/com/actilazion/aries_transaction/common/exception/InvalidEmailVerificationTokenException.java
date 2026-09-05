package com.actilazion.aries_transaction.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidEmailVerificationTokenException extends AppException {
    public InvalidEmailVerificationTokenException() {
        super("Email verification token is invalid or expired", HttpStatus.BAD_REQUEST);
    }
}
