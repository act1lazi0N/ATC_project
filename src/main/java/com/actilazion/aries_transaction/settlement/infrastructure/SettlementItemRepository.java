package com.actilazion.aries_transaction.settlement.infrastructure;

import com.actilazion.aries_transaction.settlement.domain.SettlementItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SettlementItemRepository extends JpaRepository<SettlementItem, UUID> {
    Optional<SettlementItem> findByTransaction_Id(UUID transactionId);
}
