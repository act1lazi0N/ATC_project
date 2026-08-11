package com.actilazion.aries_transaction.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "security.auth-rate-limit")
public class AuthRateLimitConfig {
    private long windowSeconds = 60;
    private int ipRequests = 60;
    private int identityRequests = 10;
    private List<String> trustedProxies = new ArrayList<>();
}
