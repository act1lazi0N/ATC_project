package com.actilazion.aries_transaction.security;

import com.actilazion.aries_transaction.common.redis.RedisCounterOperations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisCounterOperationsIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static RedisCounterOperations counterOperations;

    @BeforeAll
    static void setUpRedisClient() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        counterOperations = new RedisCounterOperations(new StringRedisTemplate(connectionFactory));
    }

    @AfterAll
    static void tearDownRedisClient() {
        connectionFactory.destroy();
    }

    @Test
    void incrementScript_setsTtlAndReturnsAtomicCount() throws InterruptedException {
        String key = "test:counter:" + System.nanoTime();

        assertThat(counterOperations.increment(key, Duration.ofSeconds(2)).count()).isEqualTo(1);
        assertThat(counterOperations.increment(key, Duration.ofSeconds(2)).count()).isEqualTo(2);
        assertThat(counterOperations.increment(key, Duration.ofSeconds(2)).ttlMillis()).isPositive();

        Thread.sleep(2_100);

        assertThat(counterOperations.increment(key, Duration.ofSeconds(2)).count()).isEqualTo(1);
    }
}
