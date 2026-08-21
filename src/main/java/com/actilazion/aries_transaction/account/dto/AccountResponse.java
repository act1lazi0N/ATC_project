package com.actilazion.aries_transaction.account.dto;


import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record AccountResponse(
        UUID id,
        UUID userId,
        String accountNumber,
        AccountType accountType,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal balance,
        String currency,
        AccountStatus status,
        OffsetDateTime createdAt,
        String description
) {
    public AccountResponse(UUID id, UUID userId, String accountNumber, AccountType accountType,
                           BigDecimal balance, String currency, AccountStatus status,
                           OffsetDateTime createdAt) {
        this(id, userId, accountNumber, accountType, balance, currency, status, createdAt, null);
    }

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getUser().getId(),
                account.getAccountNumber(),
                account.getAccountType(),
                account.getBalance(),
                account.getCurrency(),
                account.getStatus(),
                account.getCreatedAt(),
                account.getDescription()
        );
    }
}
