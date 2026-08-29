package com.actilazion.aries_transaction.transaction.dto;

public record TransactionPartyView(
        String accountNumberDisplay,
        AccountNumberExposure exposure,
        String displayName,
        boolean ownedByRequester
) {
    public static TransactionPartyView unavailable() {
        return new TransactionPartyView(null, AccountNumberExposure.UNAVAILABLE, null, false);
    }
}
