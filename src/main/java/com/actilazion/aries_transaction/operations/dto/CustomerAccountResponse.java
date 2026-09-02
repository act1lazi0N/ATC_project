package com.actilazion.aries_transaction.operations.dto;

import com.actilazion.aries_transaction.account.application.AccountPartyMasking;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;

import java.time.OffsetDateTime;

public record CustomerAccountResponse(
        String maskedAccountNumber,
        AccountType type,
        String currency,
        AccountStatus status,
        OffsetDateTime createdAt
) {
    public static CustomerAccountResponse from(Account account) {
        return new CustomerAccountResponse(
                AccountPartyMasking.maskedNumber(account),
                account.getAccountType(),
                account.getCurrency(),
                account.getStatus(),
                account.getCreatedAt()
        );
    }
}
