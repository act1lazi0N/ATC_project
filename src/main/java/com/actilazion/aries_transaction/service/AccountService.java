package com.actilazion.aries_transaction.service;

import com.actilazion.aries_transaction.dto.requests.CreateAccountRequest;
import com.actilazion.aries_transaction.dto.responses.AccountResponse;

import java.util.List;
import java.util.UUID;

public interface AccountService {
    AccountResponse create(CreateAccountRequest request, String ownerEmail);

    AccountResponse getById(UUID accountId);

    List<AccountResponse> getMyAccounts(String ownerEmail);

    AccountResponse freeze(UUID accountId);
}
