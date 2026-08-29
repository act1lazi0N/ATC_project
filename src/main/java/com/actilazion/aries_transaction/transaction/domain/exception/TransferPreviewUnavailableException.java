package com.actilazion.aries_transaction.transaction.domain.exception;

import com.actilazion.aries_transaction.common.exception.AppException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class TransferPreviewUnavailableException extends AppException {
    private final Reason reason;

    public TransferPreviewUnavailableException(Reason reason) {
        super("Transfer preview is unavailable", HttpStatus.CONFLICT);
        this.reason = reason;
    }

    public enum Reason {
        EXPIRED,
        CONSUMED
    }
}
