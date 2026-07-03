package com.actilazion.aries_transaction.transaction.exception;

import com.actilazion.aries_transaction.common.exception.AppException;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import org.springframework.http.HttpStatus;

public class InvalidTransactionStateTransitionException extends AppException {
    public InvalidTransactionStateTransitionException(TransactionStatus currentStatus, TransactionStatus targetStatus) {
        super(
                "Invalid transaction state transition: " + currentStatus + " -> " + targetStatus,
                HttpStatus.CONFLICT
        );
    }
}
