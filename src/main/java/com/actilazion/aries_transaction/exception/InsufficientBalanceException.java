package com.actilazion.aries_transaction.exception;

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
                HttpStatus.UNPROCESSABLE_ENTITY
        );
    }
}
