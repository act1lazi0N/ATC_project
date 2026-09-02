package com.actilazion.aries_transaction.ledger.infrastructure;

import com.actilazion.aries_transaction.ledger.domain.LedgerEntry;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID>, JpaSpecificationExecutor<LedgerEntry> {
    List<LedgerEntry> findAllByTransactionId(UUID transactionId);

    List<LedgerEntry> findAllByTransactionIdIn(java.util.Collection<UUID> transactionIds);

    long countByTransactionId(UUID transactionId);

    long countByTransactionIdAndEntryType(UUID transactionId, LedgerEntryType entryType);

    List<LedgerEntry> findAllByCreatedAtGreaterThanEqual(OffsetDateTime from);
}
