package com.actilazion.aries_transaction.repository;

import com.actilazion.aries_transaction.entity.Transaction;
import com.actilazion.aries_transaction.entity.enums.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    // Check idempotency key before processing
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
    boolean existsByIdempotencyKey(String idempotencyKey);

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

    Page<Transaction> findAllByStatus(TransactionStatus status, Pageable pageable);

}
