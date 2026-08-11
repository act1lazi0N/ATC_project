package com.actilazion.aries_transaction.common.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "security.ephemeral", name = "enabled", havingValue = "false")
public class InMemoryDuplicateSuppressionStore implements DuplicateSuppressionStore {
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override public Claim claim(String key, String fingerprint, long ttlSeconds) {
        Instant now = Instant.now();
        entries.computeIfPresent(key, (ignored, e) -> e.expiresAt().isBefore(now) ? null : e);
        String token = UUID.randomUUID().toString();
        Entry candidate = new Entry(fingerprint + "|" + token, now.plusSeconds(ttlSeconds));
        Entry existing = entries.putIfAbsent(key, candidate);
        if (existing == null) return new Claim(Claim.Status.ACQUIRED, token);
        return new Claim(existing.value().startsWith(fingerprint + "|") ? Claim.Status.IN_FLIGHT : Claim.Status.CONFLICT, null);
    }

    @Override public void release(String key, String ownerToken) {
        entries.computeIfPresent(key, (ignored, e) -> e.value().endsWith("|" + ownerToken) ? null : e);
    }

    private record Entry(String value, Instant expiresAt) {}
}
