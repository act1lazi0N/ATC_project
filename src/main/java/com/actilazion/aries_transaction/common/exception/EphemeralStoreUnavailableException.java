package com.actilazion.aries_transaction.common.exception;

import org.springframework.http.HttpStatus;

public class EphemeralStoreUnavailableException extends AppException {
    public EphemeralStoreUnavailableException() {
        super("Security service temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
