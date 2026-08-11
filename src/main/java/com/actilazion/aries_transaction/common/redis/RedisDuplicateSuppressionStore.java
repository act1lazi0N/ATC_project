package com.actilazion.aries_transaction.common.redis;

import com.actilazion.aries_transaction.config.RedisKeyNamespace;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "security.ephemeral", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisDuplicateSuppressionStore implements DuplicateSuppressionStore {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end", Long.class);
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyNamespace namespace;

    @Override public Claim claim(String key, String fingerprint, long ttlSeconds) {
        String redisKey = namespace.key("dup", key);
        String token = UUID.randomUUID().toString();
        String value = fingerprint + "|" + token;
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, value, Duration.ofSeconds(ttlSeconds));
            if (Boolean.TRUE.equals(acquired)) return new Claim(Claim.Status.ACQUIRED, token);
            String current = redisTemplate.opsForValue().get(redisKey);
            return current != null && current.startsWith(fingerprint + "|")
                    ? new Claim(Claim.Status.IN_FLIGHT, null)
                    : new Claim(Claim.Status.CONFLICT, null);
        } catch (RuntimeException ex) {
            return new Claim(Claim.Status.BYPASS, null);
        }
    }

    @Override public void release(String key, String ownerToken) {
        if (ownerToken == null) return;
        try { redisTemplate.execute(RELEASE, List.of(namespace.key("dup", key)), ownerToken); }
        catch (RuntimeException ignored) { }
    }
}
