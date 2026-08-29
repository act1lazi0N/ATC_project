package com.actilazion.aries_transaction.account.application;

import com.actilazion.aries_transaction.account.domain.Account;

/** Shared customer-safe account presentation used by previews and reads. */
public final class AccountPartyMasking {
    private AccountPartyMasking() {
    }

    public static String maskedNumber(Account account) {
        return maskedNumber(account == null ? null : account.getAccountNumber());
    }

    public static String maskedNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return null;
        }
        int visibleDigits = Math.min(4, accountNumber.length());
        return "*".repeat(accountNumber.length() - visibleDigits)
                + accountNumber.substring(accountNumber.length() - visibleDigits);
    }

    public static String safeDisplayName(Account account) {
        if (account == null || account.getUser() == null) {
            return null;
        }
        String fullName = account.getUser().getFullName();
        if (fullName == null || fullName.isBlank()) {
            return "***";
        }
        return fullName.length() <= 1 ? "***" : fullName.charAt(0) + "***";
    }
}
