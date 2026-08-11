package com.actilazion.aries_transaction.common.redis;

public interface DuplicateSuppressionStore {
    Claim claim(String key, String fingerprint, long ttlSeconds);
    void release(String key, String ownerToken);

    record Claim(Status status, String ownerToken) {
        public enum Status { ACQUIRED, IN_FLIGHT, CONFLICT, BYPASS }
    }
}
