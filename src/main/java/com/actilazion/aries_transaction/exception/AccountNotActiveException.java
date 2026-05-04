package com.actilazion.aries_transaction.exception;

import com.actilazion.aries_transaction.entity.enums.AccountStatus;
import org.springframework.http.HttpStatus;

import java.util.UUID;

public class AccountNotActiveException extends AppException {
    public AccountNotActiveException(UUID accountId, AccountStatus currentStatus) {
        super(
                "Account " + accountId + " is not active. Current status: " + currentStatus,
                HttpStatus.UNPROCESSABLE_CONTENT
        );
    }
}
