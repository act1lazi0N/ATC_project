package com.actilazion.aries_transaction.account.dto;

import com.actilazion.aries_transaction.account.domain.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = CreateAccountRequestDeserializer.class)
public record CreateAccountRequest(
        @NotNull(message = "Account type is required")
        AccountType accountType,

        @NotBlank(message = "Currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code, e.g. VND")
        String currency,

        @Size(max = 255)
        String description,

        @jakarta.validation.constraints.NotBlank(message = "Idempotency key is required")
        @jakarta.validation.constraints.Size(min = 16, max = 64)
        String idempotencyKey
) {
    public CreateAccountRequest(AccountType accountType, String currency, String description) {
        this(accountType, currency, description, null);
    }
}
