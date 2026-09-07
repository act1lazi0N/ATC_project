package com.actilazion.aries_transaction.identity.domain.exception;

import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class AccountSuspendedException extends AppException {
    public AccountSuspendedException() {
        super("Your account is suspended. Contact support for help.", HttpStatus.FORBIDDEN);
    }
}
