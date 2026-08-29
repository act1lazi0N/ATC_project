package com.actilazion.aries_transaction.transaction.dto;

/**
 * Authorization-aware projection contract for transaction reads.
 * Mutation responses may omit these optional fields while the read service
 * populates them on {@link TransactionResponse}.
 */
public interface TransactionReadResponse {
    TransactionPartyView fromParty();

    TransactionPartyView toParty();

    TransactionDirection direction();
}
