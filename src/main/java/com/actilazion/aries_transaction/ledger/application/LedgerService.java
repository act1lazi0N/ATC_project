package com.actilazion.aries_transaction.ledger.application;

import com.actilazion.aries_transaction.ledger.domain.LedgerEntry;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.ledger.domain.LedgerDirection;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntryType;
import com.actilazion.aries_transaction.ledger.infrastructure.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LedgerService {
    private final LedgerEntryRepository ledgerEntryRepository;

    @Transactional
    public void recordTransfer(Transaction tx) {
        recordPairedEntries(tx, LedgerEntryType.TRANSFER);
    }

    @Transactional
    public void recordReversal(Transaction tx) {
        recordPairedEntries(tx, LedgerEntryType.REVERSAL);
    }

    @Transactional
    public void recordRefund(Transaction tx) {
        recordPairedEntries(tx, LedgerEntryType.REFUND);
    }

    @Transactional
    public void recordSettlement(
            Transaction tx,
            Account clearingAccount,
            Account receiverPayableAccount,
            Account platformRevenueAccount,
            BigDecimal grossAmount,
            BigDecimal receiverPayable,
            BigDecimal platformRevenue
    ) {
        if (ledgerEntryRepository.countByTransactionIdAndEntryType(tx.getId(), LedgerEntryType.SETTLEMENT) > 0) {
            return;
        }

        List<LedgerEntry> entries = new java.util.ArrayList<>();
        entries.add(buildEntry(tx, clearingAccount, LedgerDirection.DEBIT, grossAmount, LedgerEntryType.SETTLEMENT));
        addPositiveEntry(entries, tx, receiverPayableAccount, LedgerDirection.CREDIT, receiverPayable, LedgerEntryType.SETTLEMENT);
        addPositiveEntry(entries, tx, platformRevenueAccount, LedgerDirection.CREDIT, platformRevenue, LedgerEntryType.SETTLEMENT);

        validateBalanced(entries);
        ledgerEntryRepository.saveAll(entries);
    }

    @Transactional
    public void recordSettlementAdjustment(
            Transaction tx,
            Account clearingAccount,
            Account receiverPayableAccount,
            Account platformRevenueAccount,
            BigDecimal grossAmount,
            BigDecimal receiverPayable,
            BigDecimal platformRevenue
    ) {
        if (ledgerEntryRepository.countByTransactionIdAndEntryType(tx.getId(), LedgerEntryType.ADJUSTMENT) > 0) {
            return;
        }

        List<LedgerEntry> entries = new java.util.ArrayList<>();
        addPositiveEntry(entries, tx, receiverPayableAccount, LedgerDirection.DEBIT, receiverPayable, LedgerEntryType.ADJUSTMENT);
        addPositiveEntry(entries, tx, platformRevenueAccount, LedgerDirection.DEBIT, platformRevenue, LedgerEntryType.ADJUSTMENT);
        entries.add(buildEntry(tx, clearingAccount, LedgerDirection.CREDIT, grossAmount, LedgerEntryType.ADJUSTMENT));

        validateBalanced(entries);
        ledgerEntryRepository.saveAll(entries);
    }

    private void recordPairedEntries(Transaction tx, LedgerEntryType entryType) {
        if (ledgerEntryRepository.countByTransactionId(tx.getId()) > 0) {
            return;
        }

        LedgerEntry debit = buildEntry(tx, tx.getFromAccount(), LedgerDirection.DEBIT, tx.getAmount(), entryType);

        LedgerEntry credit = buildEntry(tx, tx.getToAccount(), LedgerDirection.CREDIT, tx.getAmount(), entryType);

        List<LedgerEntry> entries = List.of(debit, credit);
        validateBalanced(entries);

        ledgerEntryRepository.saveAll(entries);
    }

    private void addPositiveEntry(
            List<LedgerEntry> entries,
            Transaction tx,
            Account account,
            LedgerDirection direction,
            BigDecimal amount,
            LedgerEntryType entryType
    ) {
        if (amount.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(buildEntry(tx, account, direction, amount, entryType));
        }
    }

    private LedgerEntry buildEntry(
            Transaction tx,
            Account account,
            LedgerDirection direction,
            BigDecimal amount,
            LedgerEntryType entryType
    ) {
        return LedgerEntry.builder()
                .transactionId(tx.getId())
                .accountId(account.getId())
                .direction(direction)
                .amount(amount)
                .currency(tx.getCurrency())
                .entryType(entryType)
                .build();
    }

    private void validateBalanced(List<LedgerEntry> entries) {
        BigDecimal totalDebit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerDirection.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerDirection.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalStateException(
                    "Unbalanced ledger entries: debit=" + totalDebit + ", credit=" + totalCredit
            );
        }
    }
}
