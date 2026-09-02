package com.actilazion.aries_transaction.audit.application;

import com.actilazion.aries_transaction.audit.domain.IdentityAuditEventType;
import com.actilazion.aries_transaction.audit.domain.IdentityAuditLog;
import com.actilazion.aries_transaction.audit.infrastructure.IdentityAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdentityAuditService {
    private final IdentityAuditLogRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(IdentityAuditEventType eventType, UUID userId, String identity, String ipAddress,
                       Map<String, Object> metadata) {
        repository.save(IdentityAuditLog.builder()
                .userId(userId)
                .eventType(eventType)
                .identityHash(identity == null ? null : hash(identity))
                .ipAddress(ipAddress)
                .metadata(metadata == null ? Map.of() : Map.copyOf(metadata))
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCustomerAdministration(
            IdentityAuditEventType eventType,
            UUID targetUserId,
            UUID actorUserId,
            String ipAddress,
            Map<String, Object> metadata
    ) {
        repository.save(IdentityAuditLog.builder()
                .userId(targetUserId)
                .actorUserId(actorUserId)
                .eventType(eventType)
                .ipAddress(ipAddress)
                .metadata(metadata == null ? Map.of() : Map.copyOf(metadata))
                .build());
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
