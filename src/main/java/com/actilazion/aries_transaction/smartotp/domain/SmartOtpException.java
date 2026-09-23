package com.actilazion.aries_transaction.smartotp.domain;

import com.actilazion.aries_transaction.identity.domain.exception.AccountSecurityException;
import org.springframework.http.HttpStatus;

public class SmartOtpException extends AccountSecurityException {
    public SmartOtpException(String code, HttpStatus status) {
        super(code, code.replace('_', ' ').toLowerCase(java.util.Locale.ROOT), status);
    }
    public static SmartOtpException conflict(String code) { return new SmartOtpException(code, HttpStatus.CONFLICT); }
    public static SmartOtpException unavailable() {
        return new SmartOtpException("SMART_OTP_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
