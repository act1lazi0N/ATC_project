package com.actilazion.aries_transaction.settlement.infrastructure;

import com.actilazion.aries_transaction.settlement.domain.SettlementItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.time.OffsetDateTime;

@Repository
public interface SettlementItemRepository extends JpaRepository<SettlementItem, UUID> {
    Optional<SettlementItem> findByTransaction_Id(UUID transactionId);

    List<SettlementItem> findAllByTransaction_OriginalTransaction_Id(UUID originalTransactionId);

    List<SettlementItem> findAllByReceiverAccount_User_IdAndCreatedAtGreaterThanEqual(
            UUID userId,
            OffsetDateTime from
    );
}
