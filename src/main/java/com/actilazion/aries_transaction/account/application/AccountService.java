package com.actilazion.aries_transaction.account.application;

import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;
import com.actilazion.aries_transaction.account.dto.AccountResponse;

import java.util.List;
import java.util.UUID;

public interface AccountService {
    AccountResponse create(CreateAccountRequest request, String ownerEmail);

    AccountResponse getById(UUID accountId, String requesterEmail);

    List<AccountResponse> getMyAccounts(String ownerEmail);

    AccountResponse freeze(UUID accountId, String requesterEmail);
}
