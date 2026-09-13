package com.actilazion.aries_transaction.identity.domain.exception;

import com.actilazion.aries_transaction.common.exception.AppException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AccountSecurityException extends AppException {
    private final String code;

    public AccountSecurityException(String code, String message, HttpStatus status) {
        super(message, status);
        this.code = code;
    }

    public static AccountSecurityException invalidResetToken() {
        return new AccountSecurityException("PASSWORD_RESET_TOKEN_INVALID",
                "Password reset token is invalid or expired", HttpStatus.BAD_REQUEST);
    }
}
