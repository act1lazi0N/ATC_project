package com.actilazion.aries_transaction.ledger.infrastructure;

import com.actilazion.aries_transaction.ledger.domain.LedgerEntry;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    List<LedgerEntry> findAllByTransactionId(UUID transactionId);

    long countByTransactionId(UUID transactionId);

    long countByTransactionIdAndEntryType(UUID transactionId, LedgerEntryType entryType);
}
