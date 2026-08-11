package com.actilazion.aries_transaction.identity.infrastructure;

import com.actilazion.aries_transaction.identity.application.LoginAttemptStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnProperty(prefix = "security.ephemeral", name = "enabled", havingValue = "false")
public class InMemoryLoginAttemptStore implements LoginAttemptStore {
    private final ConcurrentMap<String, Window> attempts = new ConcurrentHashMap<>();

    @Override
    public void ensureAvailable() {
    }

    @Override
    public long recordFailure(String key, Duration ttl) {
        Instant now = Instant.now();
        Window current = attempts.compute(key, (ignored, existing) -> {
            if (existing == null || existing.expiresAt().isBefore(now)) {
                return new Window(now.plus(ttl), 1);
            }
            return new Window(existing.expiresAt(), existing.count() + 1);
        });
        return current.count();
    }

    @Override
    public void clear(String key) {
        attempts.remove(key);
    }

    private record Window(Instant expiresAt, long count) {
    }
}
