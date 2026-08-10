package com.actilazion.aries_transaction.common.exception;

import org.springframework.http.HttpStatus;

public class RateLimitExceededException extends AppException {
    public RateLimitExceededException() {
        super("Too many authentication requests", HttpStatus.TOO_MANY_REQUESTS);
    }
}
