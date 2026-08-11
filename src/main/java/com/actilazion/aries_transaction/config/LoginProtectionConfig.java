package com.actilazion.aries_transaction.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "identity.login")
public class LoginProtectionConfig {
    private int maxFailedAttempts = 5;
    private long lockDurationSeconds = 900L;
}
