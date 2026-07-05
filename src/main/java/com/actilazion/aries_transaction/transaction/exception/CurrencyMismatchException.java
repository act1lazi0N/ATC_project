package com.actilazion.aries_transaction.transaction.exception;

import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class CurrencyMismatchException extends AppException {
    public CurrencyMismatchException(String message) {
        super(message, HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
