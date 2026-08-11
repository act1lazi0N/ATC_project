package com.actilazion.aries_transaction.identity.infrastructure.redis;

import com.actilazion.aries_transaction.common.redis.RedisCounterOperations;
import com.actilazion.aries_transaction.config.RedisKeyNamespace;
import com.actilazion.aries_transaction.identity.application.LoginAttemptStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "security.ephemeral", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisLoginAttemptStore implements LoginAttemptStore {
    private final RedisCounterOperations counterOperations;
    private final RedisKeyNamespace keyNamespace;

    @Override
    public void ensureAvailable() {
        counterOperations.ensureAvailable();
    }

    @Override
    public long recordFailure(String key, Duration ttl) {
        return counterOperations.increment(keyNamespace.key("login-failure", key), ttl).count();
    }

    @Override
    public void clear(String key) {
        counterOperations.delete(keyNamespace.key("login-failure", key));
    }
}
