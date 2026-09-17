package com.actilazion.aries_transaction.payment.domain;

import com.actilazion.aries_transaction.common.exception.AppException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class QrException extends AppException {
    private final String code;

    public QrException(String code, String message, HttpStatus status) {
        super(message, status);
        this.code = code;
    }

    public static QrException unavailable() {
        return new QrException("QR_UNAVAILABLE", "QR code is unavailable", HttpStatus.NOT_FOUND);
    }
}
