package com.actilazion.aries_transaction.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
@RequiredArgsConstructor
public class RefreshCookieProductionValidator {
    private final RefreshCookiePolicy policy;
    private final Environment environment;

    @PostConstruct
    void validate() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        var origins = policy.allowedOriginList();
        if (origins.isEmpty()) {
            throw invalidProductionOrigin("<empty>", null);
        }
        for (String origin : origins) {
            String host;
            try {
                host = URI.create(origin).getHost();
            } catch (IllegalArgumentException ex) {
                throw invalidProductionOrigin(origin, ex);
            }
            if (host == null || isLoopbackHost(host)) {
                throw invalidProductionOrigin(origin, null);
            }
        }
    }

    private boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
    }

    private IllegalStateException invalidProductionOrigin(String origin, Exception cause) {
        String message = "Production refresh origins must be explicit non-loopback origins: " + origin;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }
}
