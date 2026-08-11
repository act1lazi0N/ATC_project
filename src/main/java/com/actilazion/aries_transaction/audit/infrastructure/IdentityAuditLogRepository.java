package com.actilazion.aries_transaction.audit.infrastructure;

import com.actilazion.aries_transaction.audit.domain.IdentityAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.List;

@Repository
public interface IdentityAuditLogRepository extends JpaRepository<IdentityAuditLog, UUID> {
    List<IdentityAuditLog> findAllByUserIdOrderByCreatedAtAsc(UUID userId);
}
