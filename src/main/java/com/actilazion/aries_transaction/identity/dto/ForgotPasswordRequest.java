package com.actilazion.aries_transaction.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 255) String email) {
    public ForgotPasswordRequest {
        if (email != null) email = email.trim();
    }
    @Override public String toString() { return "ForgotPasswordRequest[REDACTED]"; }
}
