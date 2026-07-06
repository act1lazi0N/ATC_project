package com.actilazion.aries_transaction.settlement.persistence;

import com.actilazion.aries_transaction.settlement.domain.PayoutAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PayoutAttemptRepository extends JpaRepository<PayoutAttempt, UUID> {
    List<PayoutAttempt> findAllBySettlementItemIdOrderByAttemptedAtAsc(UUID settlementItemId);
}
