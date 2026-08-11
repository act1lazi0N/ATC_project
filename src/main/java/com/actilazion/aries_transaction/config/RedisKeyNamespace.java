package com.actilazion.aries_transaction.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RedisKeyNamespace {
    private final RedisEphemeralProperties properties;

    public String key(String... parts) {
        return properties.getKeyPrefix() + ":" + Arrays.stream(parts)
                .map(this::sanitize)
                .collect(Collectors.joining(":"));
    }

    private String sanitize(String value) {
        return value == null ? "null" : value.replace(':', '_');
    }
}
