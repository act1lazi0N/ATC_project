package com.actilazion.aries_transaction.transaction.domain.exception;


import com.actilazion.aries_transaction.common.exception.AppException;
public class SelfTransferException extends RuntimeException {
    public SelfTransferException(String message) {
        super(message);
    }
}
