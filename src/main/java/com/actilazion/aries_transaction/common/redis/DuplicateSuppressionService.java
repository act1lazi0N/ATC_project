package com.actilazion.aries_transaction.common.redis;

import com.actilazion.aries_transaction.common.exception.AppException;
import com.actilazion.aries_transaction.config.RedisEphemeralProperties;
import com.actilazion.aries_transaction.config.RedisKeyNamespace;
import com.actilazion.aries_transaction.common.redis.SecurityKeyHasher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Service
public class DuplicateSuppressionService {
    private final DuplicateSuppressionStore store;
    private final RedisEphemeralProperties properties;
    private final RedisKeyNamespace namespace;
    private final SecurityKeyHasher hasher;

    public DuplicateSuppressionService(DuplicateSuppressionStore store, RedisEphemeralProperties properties,
                                       RedisKeyNamespace namespace, SecurityKeyHasher hasher) {
        this.store = store; this.properties = properties; this.namespace = namespace; this.hasher = hasher;
    }

    public <T> T execute(String operation, String owner, String idempotencyKey, String fingerprint, Supplier<T> action) {
        String key = hasher.hash(operation + ":" + owner + ":" + idempotencyKey);
        DuplicateSuppressionStore.Claim claim = store.claim(key, fingerprint, properties.getDuplicateSuppressionTtlSeconds());
        if (claim.status() == DuplicateSuppressionStore.Claim.Status.IN_FLIGHT)
            throw new DuplicateInFlightException(idempotencyKey);
        if (claim.status() == DuplicateSuppressionStore.Claim.Status.CONFLICT)
            throw new DuplicateConflictException(idempotencyKey);
        try { return action.get(); } finally {
            if (claim.status() == DuplicateSuppressionStore.Claim.Status.ACQUIRED) store.release(key, claim.ownerToken());
        }
    }

    public static class DuplicateInFlightException extends AppException {
        public DuplicateInFlightException(String key) { super("Duplicate request in progress: " + key, HttpStatus.CONFLICT); }
    }
    public static class DuplicateConflictException extends AppException {
        public DuplicateConflictException(String key) { super("Duplicate request conflicts with existing request: " + key, HttpStatus.CONFLICT); }
    }
}
