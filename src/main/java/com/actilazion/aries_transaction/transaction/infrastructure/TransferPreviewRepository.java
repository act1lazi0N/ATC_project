package com.actilazion.aries_transaction.transaction.infrastructure;

import com.actilazion.aries_transaction.transaction.domain.TransferPreview;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TransferPreviewRepository extends JpaRepository<TransferPreview, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from TransferPreview p join fetch p.sourceAccount join fetch p.destinationAccount where p.id = :id")
    Optional<TransferPreview> findByIdWithLock(@Param("id") UUID id);
}
