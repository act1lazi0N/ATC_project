package com.actilazion.aries_transaction.ledger.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.transaction.domain.Transaction;

import java.math.BigDecimal;

public interface LedgerService {
    void recordTransfer(Transaction tx);
    void recordReversal(Transaction tx);
    void recordRefund(Transaction tx);
    void recordSettlement(Transaction tx, Account clearingAccount, Account receiverPayableAccount,
                          Account platformRevenueAccount, BigDecimal grossAmount,
                          BigDecimal receiverPayable, BigDecimal platformRevenue);
    void recordSettlementAdjustment(Transaction tx, Account clearingAccount, Account receiverPayableAccount,
                                    Account platformRevenueAccount, BigDecimal grossAmount,
                                    BigDecimal receiverPayable, BigDecimal platformRevenue);
}
