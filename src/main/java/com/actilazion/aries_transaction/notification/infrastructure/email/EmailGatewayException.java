package com.actilazion.aries_transaction.notification.infrastructure.email;

public class EmailGatewayException extends RuntimeException {
    private final boolean retryable;
    private final String safeCode;

    public EmailGatewayException(boolean retryable, String safeCode, Throwable cause) {
        super(safeCode, cause);
        this.retryable = retryable;
        this.safeCode = safeCode;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getSafeCode() {
        return safeCode;
    }
}
