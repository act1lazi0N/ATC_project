package com.actilazion.aries_transaction.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmEmailVerificationRequest(
        @NotBlank @Size(max = 200) String token
) {
}
