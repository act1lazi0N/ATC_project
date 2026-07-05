package com.actilazion.aries_transaction.ledger.application;

import com.actilazion.aries_transaction.ledger.domain.LedgerEntry;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.ledger.domain.LedgerDirection;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntryType;
import com.actilazion.aries_transaction.ledger.persistence.LedgerEntryRepository;
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
        if (ledgerEntryRepository.countByTransactionId(tx.getId()) > 0) {
            return;
        }

        LedgerEntry debit = LedgerEntry.builder()
                .transaction(tx)
                .account(tx.getFromAccount())
                .direction(LedgerDirection.DEBIT)
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .entryType(LedgerEntryType.TRANSFER)
                .build();

        LedgerEntry credit = LedgerEntry.builder()
                .transaction(tx)
                .account(tx.getToAccount())
                .direction(LedgerDirection.CREDIT)
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .entryType(LedgerEntryType.TRANSFER)
                .build();

        List<LedgerEntry> entries = List.of(debit, credit);
        validateBalanced(entries);

        ledgerEntryRepository.saveAll(entries);
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
