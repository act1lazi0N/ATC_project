package com.actilazion.aries_transaction.settlement.persistence;

import com.actilazion.aries_transaction.settlement.domain.SettlementBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, UUID> {
}
