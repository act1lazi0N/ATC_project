package com.actilazion.aries_transaction.transaction.domain.exception;

import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class InvalidTransferAmountException extends AppException {
    public InvalidTransferAmountException() {
        super("Transfer amount must be at least 1000 and have at most two decimal places", HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
