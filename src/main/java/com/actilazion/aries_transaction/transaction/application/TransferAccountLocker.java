package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TransferAccountLocker {
    private final AccountRepository accountRepository;

    public AccountPair lock(UUID fromAccountId, UUID toAccountId) {
        Account fromAccount;
        Account toAccount;
        if (fromAccountId.compareTo(toAccountId) < 0) {
            fromAccount = lockAccount(fromAccountId);
            toAccount = lockAccount(toAccountId);
        } else {
            toAccount = lockAccount(toAccountId);
            fromAccount = lockAccount(fromAccountId);
        }
        return new AccountPair(fromAccount, toAccount);
    }

    private Account lockAccount(UUID accountId) {
        return accountRepository.findByIdWithLock(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    public record AccountPair(Account fromAccount, Account toAccount) {
    }
}
