package com.actilazion.aries_transaction.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {
    private static final String KEY_PREFIX = "transfer:idem:";
    private static final Duration TTL      = Duration.ofHours(24);
    private final StringRedisTemplate redisTemplate;

    /**
     * Check an idempotency key exists in redis or not.
     * @param idempotencyKey
     * @return true if an idempotency key does not exist in redis, false otherwise
     */
    public boolean tryConsume(String idempotencyKey) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        Boolean inserted = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", TTL);
        return Boolean.TRUE.equals(inserted);
    }

    /**
     * Delete key from redis when transaction is failed.
     * @param idempotencyKey
     */
    public void release(String idempotencyKey) {
        redisTemplate.delete(KEY_PREFIX + idempotencyKey);
        log.debug("Released idempotency key: {}", idempotencyKey);
    }


}
