package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.account.application.AccountPartyMasking;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.dto.AccountNumberExposure;
import com.actilazion.aries_transaction.transaction.dto.TransactionDirection;
import com.actilazion.aries_transaction.transaction.dto.TransactionPartyView;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;

import java.util.UUID;

/** Maps a transaction to a requester-safe read representation. */
public final class TransactionReadProjection {
    private TransactionReadProjection() {
    }

    public static TransactionResponse project(Transaction transaction, User requester, UUID selectedAccountId) {
        Account from = transaction.getFromAccount();
        Account to = transaction.getToAccount();
        boolean ownsFrom = owns(from, requester);
        boolean ownsTo = owns(to, requester);
        return TransactionResponse.from(
                transaction,
                party(from, ownsFrom),
                party(to, ownsTo),
                direction(from, to, ownsFrom, ownsTo, selectedAccountId)
        );
    }

    private static boolean owns(Account account, User requester) {
        return account != null && account.getUser() != null && requester != null
                && requester.getId() != null && requester.getId().equals(account.getUser().getId());
    }

    private static TransactionPartyView party(Account account, boolean owned) {
        if (account == null || account.getAccountNumber() == null || account.getAccountNumber().isBlank()
                || account.getUser() == null) {
            return TransactionPartyView.unavailable();
        }
        if (owned) {
            return new TransactionPartyView(
                    account.getAccountNumber(),
                    AccountNumberExposure.FULL_OWNED,
                    account.getUser().getFullName(),
                    true);
        }
        return new TransactionPartyView(
                AccountPartyMasking.maskedNumber(account),
                AccountNumberExposure.MASKED_COUNTERPARTY,
                AccountPartyMasking.safeDisplayName(account),
                false);
    }

    private static TransactionDirection direction(
            Account from,
            Account to,
            boolean ownsFrom,
            boolean ownsTo,
            UUID selectedAccountId
    ) {
        if (selectedAccountId != null) {
            boolean selectedFrom = from != null && selectedAccountId.equals(from.getId());
            boolean selectedTo = to != null && selectedAccountId.equals(to.getId());
            if (selectedFrom && selectedTo) {
                return TransactionDirection.OWN_ACCOUNTS;
            }
            if ((selectedFrom || selectedTo) && ownsFrom && ownsTo) {
                return TransactionDirection.OWN_ACCOUNTS;
            }
            if (selectedFrom && ownsFrom) {
                return TransactionDirection.OUTGOING;
            }
            if (selectedTo && ownsTo) {
                return TransactionDirection.INCOMING;
            }
            return TransactionDirection.UNKNOWN;
        }
        if (ownsFrom && ownsTo) {
            return TransactionDirection.OWN_ACCOUNTS;
        }
        if (ownsFrom) {
            return TransactionDirection.OUTGOING;
        }
        if (ownsTo) {
            return TransactionDirection.INCOMING;
        }
        return TransactionDirection.UNKNOWN;
    }
}
