package com.actilazion.aries_transaction.account.dto;

import com.actilazion.aries_transaction.account.domain.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotNull(message = "accountType is required")
        AccountType accountType,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO code, e.g. VND")
        String currency,

        @Size(max = 255)
        String description
) {
}
