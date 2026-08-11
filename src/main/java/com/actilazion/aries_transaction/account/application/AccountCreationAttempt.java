package com.actilazion.aries_transaction.account.application;

import com.actilazion.aries_transaction.account.dto.AccountResponse;
import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;

public interface AccountCreationAttempt {
    AccountResponse create(CreateAccountRequest request, String ownerEmail);
}
