package com.actilazion.aries_transaction.transaction.domain.exception;

import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class RecipientUnavailableException extends AppException {
    public RecipientUnavailableException() {
        super("Recipient is unavailable", HttpStatus.NOT_FOUND);
    }
}
