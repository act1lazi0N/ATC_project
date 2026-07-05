package com.actilazion.aries_transaction.settlement.exception;

import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class NoSettlementCandidateException extends AppException {
    public NoSettlementCandidateException(String currency) {
        super("No completed transactions available for settlement in currency " + currency, HttpStatus.CONFLICT);
    }
}
