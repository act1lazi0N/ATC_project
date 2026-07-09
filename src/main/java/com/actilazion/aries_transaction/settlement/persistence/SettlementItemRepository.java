package com.actilazion.aries_transaction.settlement.persistence;

import com.actilazion.aries_transaction.settlement.domain.SettlementItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SettlementItemRepository extends JpaRepository<SettlementItem, UUID> {
}
