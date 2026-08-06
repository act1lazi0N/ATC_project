package com.actilazion.aries_transaction.reconciliation.domain.exception;

import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class ReportingSnapshotClientUnavailableException extends AppException {
    public ReportingSnapshotClientUnavailableException(String message) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    public ReportingSnapshotClientUnavailableException(String message, Throwable cause) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE, cause);
    }
}
