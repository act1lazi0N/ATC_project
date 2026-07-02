package com.actilazion.aries_transaction.service;

import com.actilazion.aries_transaction.entity.LedgerEntry;
import com.actilazion.aries_transaction.entity.Transaction;
import com.actilazion.aries_transaction.entity.enums.LedgerDirection;
import com.actilazion.aries_transaction.entity.enums.LedgerEntryType;
import com.actilazion.aries_transaction.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        ledgerEntryRepository.save(debit);
        ledgerEntryRepository.save(credit);
    }
}
