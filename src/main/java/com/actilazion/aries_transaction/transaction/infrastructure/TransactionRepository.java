package com.actilazion.aries_transaction.transaction.infrastructure;

import com.actilazion.aries_transaction.transaction.domain.Transaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    @Query("""
    SELECT tx FROM Transaction tx
    JOIN FETCH tx.fromAccount fromAccount
    JOIN FETCH fromAccount.user
    JOIN FETCH tx.toAccount toAccount
    JOIN FETCH toAccount.user
    LEFT JOIN FETCH tx.originalTransaction
    WHERE tx.id = :id
    """)
    Optional<Transaction> findWebhookAggregateById(@Param("id") UUID id);

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
    WHERE t.fromAccount.user.id = :userId
       OR t.toAccount.user.id = :userId
    ORDER BY t.createdAt DESC, t.id DESC
    """)
    Page<Transaction> findRecentByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
    SELECT DISTINCT t FROM Transaction t
    JOIN FETCH t.fromAccount fromAccount
    JOIN FETCH fromAccount.user
    JOIN FETCH t.toAccount toAccount
    JOIN FETCH toAccount.user
    WHERE (fromAccount.user.id = :userId OR toAccount.user.id = :userId)
      AND t.createdAt >= :from
      AND t.createdAt < :to
    ORDER BY t.createdAt ASC, t.id ASC
    """)
    List<Transaction> findDashboardTransactions(
            @Param("userId") UUID userId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to
    );

    List<Transaction> findAllByCreatedAtGreaterThanEqual(OffsetDateTime from);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    SELECT t FROM Transaction t
    WHERE t.currency = :currency
      AND t.completedAt <= :cutoffCompletedAt
      AND NOT EXISTS (
          SELECT 1 FROM SettlementItem item
          WHERE item.transaction = t
      )
      AND (
          (
              t.originalTransaction IS NULL
              AND (
                  t.status = com.actilazion.aries_transaction.transaction.domain.TransactionStatus.COMPLETED
                  OR t.status = com.actilazion.aries_transaction.transaction.domain.TransactionStatus.PARTIALLY_REFUNDED
                  OR t.status = com.actilazion.aries_transaction.transaction.domain.TransactionStatus.REFUNDED
              )
          )
          OR (
              t.status = com.actilazion.aries_transaction.transaction.domain.TransactionStatus.COMPLETED
              AND
              t.originalTransaction IS NOT NULL
              AND EXISTS (
                  SELECT 1 FROM SettlementItem originalItem
                  WHERE originalItem.transaction = t.originalTransaction
                    AND originalItem.itemType = com.actilazion.aries_transaction.settlement.domain.SettlementItemType.NORMAL
                    AND t.completedAt > originalItem.createdAt
              )
          )
      )
    ORDER BY t.completedAt ASC
    """)
    List<Transaction> findSettlementCandidates(
            @Param("currency") String currency,
            @Param("cutoffCompletedAt") OffsetDateTime cutoffCompletedAt
    );

    @Query("""
    SELECT COALESCE(SUM(t.amount), 0)
    FROM Transaction t
    WHERE t.originalTransaction.id = :originalTransactionId
      AND t.operation = com.actilazion.aries_transaction.transaction.domain.TransactionOperation.REFUND
      AND t.status = com.actilazion.aries_transaction.transaction.domain.TransactionStatus.COMPLETED
      AND t.completedAt <= :cutoffCompletedAt
    """)
    BigDecimal sumCompletedRefundAmount(
            @Param("originalTransactionId") UUID originalTransactionId,
            @Param("cutoffCompletedAt") OffsetDateTime cutoffCompletedAt
    );

    @Query("""
    SELECT t FROM Transaction t
    WHERE t.currency = :currency
      AND t.completedAt IS NOT NULL
      AND t.completedAt >= :windowStart
      AND t.completedAt < :windowEnd
    ORDER BY t.completedAt ASC
    """)
    List<Transaction> findForReconciliation(
            @Param("currency") String currency,
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd
    );

}
