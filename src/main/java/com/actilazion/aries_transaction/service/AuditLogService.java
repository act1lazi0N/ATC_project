package com.actilazion.aries_transaction.service;

import com.actilazion.aries_transaction.entity.AuditLog;
import com.actilazion.aries_transaction.entity.Transaction;
import com.actilazion.aries_transaction.entity.enums.AuditEventType;
import com.actilazion.aries_transaction.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final AuditLogRepository auditLogRepository;
    public void log (Transaction tx, AuditEventType eventType, String actorId) {
        Map<String, Object> payload = buildPayload(tx);

        AuditLog auditLog = AuditLog.builder()
                .transaction(tx)
                .eventType(eventType)
                .actorId(actorId)
                .payload(payload)
                .build();
        auditLogRepository.save(auditLog);
        log.info("[AUDIT] txId={} event={} actor={}", tx.getId(), eventType, actorId);
    }

    private Map<String, Object> buildPayload(Transaction tx) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId",   tx.getId().toString());
        payload.put("fromAccountId",   tx.getFromAccount().getId().toString());
        payload.put("toAccountId",     tx.getToAccount().getId().toString());
        payload.put("amount",          tx.getAmount().toPlainString());
        payload.put("currency",        tx.getCurrency());
        payload.put("status",          tx.getStatus().name());
        payload.put("idempotencyKey",  tx.getIdempotencyKey());
        if (tx.getFailureReason() != null) {
            payload.put("failureReason", tx.getFailureReason());
        }
        return payload;
    }

}
