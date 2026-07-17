package com.actilazion.aries_transaction.transaction.domain.exception;


import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class SelfTransferException extends AppException {
    public SelfTransferException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }
}
