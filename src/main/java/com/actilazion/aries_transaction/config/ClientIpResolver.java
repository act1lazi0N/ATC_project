package com.actilazion.aries_transaction.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ClientIpResolver {
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final AuthRateLimitConfig config;

    public String resolve(HttpServletRequest request) {
        String remoteAddress = normalize(request.getRemoteAddr());
        if (!isTrusted(remoteAddress)) {
            return remoteAddress;
        }

        String forwardedFor = request.getHeader(FORWARDED_FOR);
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddress;
        }

        List<String> chain = Arrays.stream(forwardedFor.split(","))
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .toList();
        for (int index = chain.size() - 1; index >= 0; index--) {
            String candidate = chain.get(index);
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        return remoteAddress;
    }

    private String normalize(String value) {
        return value == null ? "unknown" : value.trim();
    }

    private boolean isTrusted(String address) {
        return config.getTrustedProxies().stream()
                .map(String::trim)
                .anyMatch(address::equals);
    }
}
