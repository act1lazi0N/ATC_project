package com.actilazion.aries_transaction.payment.infrastructure;

import com.actilazion.aries_transaction.payment.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.UUID;

public interface PaymentQrRepository extends JpaRepository<PaymentQrCode, UUID> {
    Optional<PaymentQrCode> findByOwnerIdAndIdempotencyKey(UUID ownerId, String idempotencyKey);
    boolean existsByAccountIdAndTypeAndState(UUID accountId, QrType type, QrState state);
    Page<PaymentQrCode> findByAccountId(UUID accountId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from PaymentQrCode q where q.id = :id")
    Optional<PaymentQrCode> findByIdWithLock(@Param("id") UUID id);
}
