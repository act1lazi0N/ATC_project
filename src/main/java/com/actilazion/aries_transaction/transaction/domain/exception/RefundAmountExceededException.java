package com.actilazion.aries_transaction.transaction.domain.exception;

import com.actilazion.aries_transaction.common.exception.AppException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

public class RefundAmountExceededException extends AppException {
    public RefundAmountExceededException(BigDecimal requested, BigDecimal remaining) {
        super(
                "Refund amount exceeds remaining refundable amount. Requested: "
                        + requested.toPlainString()
                        + ", Remaining: "
                        + remaining.toPlainString(),
                HttpStatus.UNPROCESSABLE_CONTENT
        );
    }
}
