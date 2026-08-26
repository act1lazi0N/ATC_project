package com.actilazion.aries_transaction.account.domain.exception;

import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class AccountLimitExceededException extends AppException {
    public AccountLimitExceededException() {
        super("Maximum of 5 active accounts is allowed", HttpStatus.CONFLICT);
    }
}
