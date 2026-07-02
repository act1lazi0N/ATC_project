package com.actilazion.aries_transaction.transaction.exception;


import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

public class InsufficientBalanceException extends AppException {
    public InsufficientBalanceException(BigDecimal available, BigDecimal required) {
        super(
                String.format(
                        "Insufficient balance. Available: %s, Required: %s",
                        available.toPlainString(),
                        required.toPlainString()
                ),
                HttpStatus.UNPROCESSABLE_CONTENT
        );
    }
}
