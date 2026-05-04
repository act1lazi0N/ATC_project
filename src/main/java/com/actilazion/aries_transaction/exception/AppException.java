package com.actilazion.aries_transaction.exception;

import org.springframework.http.HttpStatus;

public abstract class AppException extends RuntimeException{
    private final HttpStatus httpStatus;

    protected AppException(String message, HttpStatus httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
