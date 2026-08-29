package com.actilazion.aries_transaction.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class RateLimitExceededException extends AppException {
    private final long retryAfterSeconds;

    public RateLimitExceededException() {
        this(1);
    }

    public RateLimitExceededException(long retryAfterSeconds) {
        super("Too many requests", HttpStatus.TOO_MANY_REQUESTS);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

}
