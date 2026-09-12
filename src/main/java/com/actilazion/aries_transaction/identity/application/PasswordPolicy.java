package com.actilazion.aries_transaction.identity.application;

import com.actilazion.aries_transaction.identity.domain.exception.AccountSecurityException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    private PasswordPolicy() { }

    public static void validateNew(String password) {
        if (password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new AccountSecurityException("VALIDATION_ERROR",
                    "Password must be 8-72 characters",
                    HttpStatus.BAD_REQUEST);
        }
    }
}
