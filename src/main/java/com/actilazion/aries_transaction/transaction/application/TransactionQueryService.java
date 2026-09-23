package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.common.exception.ForbiddenOperationException;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionQueryService {
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public TransactionResponse getById(UUID txId, String requesterEmail) {
        Transaction tx = transactionRepository.findById(txId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", txId));
        User requester = findRequester(requesterEmail);
        assertCanReadTransaction(tx, requester);
        return TransactionReadProjection.project(tx, requester, null);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getByAccount(UUID accountId, Pageable pageable, String requesterEmail) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        User requester = findRequester(requesterEmail);
        assertCanReadAccount(account, requester);
        return transactionRepository.findAllByAccountId(accountId, pageable)
                .map(transaction -> TransactionReadProjection.project(transaction, requester, accountId));
    }

    private void assertCanReadTransaction(Transaction tx, User requester) {
        if (isPrivileged(requester)
                || isAccountOwner(tx.getFromAccount(), requester)
                || isAccountOwner(tx.getToAccount(), requester)) {
            return;
        }
        throw new ForbiddenOperationException("Not allowed to read this transaction");
    }

    private void assertCanReadAccount(Account account, User requester) {
        if (isPrivileged(requester) || isAccountOwner(account, requester)) {
            return;
        }
        throw new ForbiddenOperationException("Not allowed to read this account history");
    }

    private boolean isPrivileged(User requester) {
        return requester.getRole() == Role.ADMIN || requester.getRole() == Role.OPERATOR;
    }

    private boolean isAccountOwner(Account account, User requester) {
        return account != null && account.getUser() != null
                && account.getUser().getId() != null
                && requester != null && requester.getId() != null
                && account.getUser().getId().equals(requester.getId());
    }

    private User findRequester(String requesterEmail) {
        return userRepository.findByEmail(requesterEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", requesterEmail));
    }
}
