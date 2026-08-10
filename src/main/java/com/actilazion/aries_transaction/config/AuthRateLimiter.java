package com.actilazion.aries_transaction.config;

import com.actilazion.aries_transaction.common.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@RequiredArgsConstructor
public class AuthRateLimiter {
    private final AuthRateLimitConfig config;
    private final Clock clock = Clock.systemUTC();
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public void check(String endpoint, HttpServletRequest request, String identity) {
        checkBucket(endpoint + ":ip:" + request.getRemoteAddr(), config.getIpRequests());
        if (identity != null && !identity.isBlank()) {
            checkBucket(endpoint + ":identity:" + digest(identity.trim().toLowerCase()), config.getIdentityRequests());
        }
    }

    private void checkBucket(String key, int limit) {
        Instant now = clock.instant();
        Window window = windows.compute(key, (ignored, current) -> {
            if (current == null || current.expiresAt().isBefore(now)) {
                return new Window(now.plusSeconds(config.getWindowSeconds()), 1);
            }
            if (current.count() >= limit) {
                throw new RateLimitExceededException();
            }
            return new Window(current.expiresAt(), current.count() + 1);
        });
        if (window == null) {
            throw new RateLimitExceededException();
        }
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record Window(Instant expiresAt, int count) { }
}
