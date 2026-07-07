package com.actilazion.aries_transaction.account.domain.exception;

import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class InternalAccountTypeException extends AppException {
    public InternalAccountTypeException(AccountType accountType) {
        super("Internal account type cannot be created through user API: " + accountType, HttpStatus.BAD_REQUEST);
    }
}
