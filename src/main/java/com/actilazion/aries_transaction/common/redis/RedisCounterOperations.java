package com.actilazion.aries_transaction.common.redis;

import com.actilazion.aries_transaction.common.exception.EphemeralStoreUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "security.ephemeral", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisCounterOperations {
    private static final DefaultRedisScript<List> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1]) " +
                    "if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end " +
                    "local ttl = redis.call('PTTL', KEYS[1]) " +
                    "return {current, ttl}",
            List.class
    );

    private final StringRedisTemplate redisTemplate;
    public CounterResult increment(String key, Duration ttl) {
        try {
            List<?> result = redisTemplate.execute(
                    INCREMENT_SCRIPT,
                    List.of(key),
                    Long.toString(ttl.toMillis())
            );
            if (result == null || result.size() < 2) {
                throw new EphemeralStoreUnavailableException();
            }
            return new CounterResult(number(result.get(0)), number(result.get(1)));
        } catch (EphemeralStoreUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new EphemeralStoreUnavailableException();
        }
    }

    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException ex) {
            throw new EphemeralStoreUnavailableException();
        }
    }

    public void ensureAvailable() {
        try {
            redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
        } catch (RuntimeException ex) {
            throw new EphemeralStoreUnavailableException();
        }
    }

    public record CounterResult(long count, long ttlMillis) {
    }

    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }
}
