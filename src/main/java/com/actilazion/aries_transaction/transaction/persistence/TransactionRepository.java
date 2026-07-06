package com.actilazion.aries_transaction.transaction.persistence;

import com.actilazion.aries_transaction.transaction.domain.Transaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.id = :id")
    Optional<Transaction> findByIdWithLock(@Param("id") UUID id);

    // Find all transactions by account id, arranged in ascending order of creation date
    @Query("""
    SELECT t FROM Transaction t
    WHERE t.fromAccount.id = :accountId
       OR t.toAccount.id   = :accountId
    ORDER BY t.createdAt DESC
    """)
    Page<Transaction> findAllByAccountId(
            @Param("accountId") UUID accountId,
        Pageable pageable
    );

    @Query("""
    SELECT t FROM Transaction t
    WHERE t.status = com.actilazion.aries_transaction.transaction.domain.TransactionStatus.COMPLETED
      AND t.currency = :currency
      AND t.completedAt <= :cutoffCompletedAt
      AND t.originalTransaction IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM Transaction related
          WHERE related.originalTransaction = t
      )
      AND NOT EXISTS (
          SELECT 1 FROM SettlementItem item
          WHERE item.transaction = t
      )
    ORDER BY t.completedAt ASC
    """)
    List<Transaction> findSettlementCandidates(
            @Param("currency") String currency,
            @Param("cutoffCompletedAt") OffsetDateTime cutoffCompletedAt
    );

}
