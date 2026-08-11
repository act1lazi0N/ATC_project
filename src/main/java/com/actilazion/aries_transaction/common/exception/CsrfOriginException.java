package com.actilazion.aries_transaction.common.exception;

import org.springframework.http.HttpStatus;

public class CsrfOriginException extends AppException {
    public CsrfOriginException() {
        super("Invalid refresh request origin", HttpStatus.FORBIDDEN);
    }
}
