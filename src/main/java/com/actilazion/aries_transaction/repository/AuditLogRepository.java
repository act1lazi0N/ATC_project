package com.actilazion.aries_transaction.repository;

import com.actilazion.aries_transaction.entity.AuditLog;
import com.actilazion.aries_transaction.entity.enums.AuditEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    List<AuditLog> findAllByTransactionId(UUID transactionId);
    List<AuditLog> findAllByTransactionIdAndEventType(UUID transactionId, AuditEventType eventType);
}
