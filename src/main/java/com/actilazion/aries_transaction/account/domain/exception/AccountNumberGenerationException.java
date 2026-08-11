package com.actilazion.aries_transaction.account.domain.exception;

import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class AccountNumberGenerationException extends AppException {
    public AccountNumberGenerationException(int maxAttempts, Throwable cause) {
        super("Unable to allocate a unique account number after " + maxAttempts + " attempts", HttpStatus.CONFLICT, cause);
    }
}
