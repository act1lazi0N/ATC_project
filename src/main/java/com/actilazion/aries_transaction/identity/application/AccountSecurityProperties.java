package com.actilazion.aries_transaction.identity.application;

import com.actilazion.aries_transaction.identity.domain.exception.AccountSecurityException;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "security.account-security")
@Validated
@Getter
@Setter
public class AccountSecurityProperties {
    private boolean enabled;
    private String resetSigningKey = "";
    private String resetPublicUrl = "";
    private Duration resetTtl = Duration.ofMinutes(15);
    @Min(1) private int forgotEmailRequests = 3;
    @Min(1) private int forgotIpRequests = 20;
    @Min(1) private long forgotWindowSeconds = 900;
    @Min(1) private long resendSeconds = 60;
    @Min(0) private long minimumResponseMillis = 200;
    @Min(1) private int retentionDays = 30;

    public void requireEnabled() {
        if (!enabled) {
            throw new AccountSecurityException("FEATURE_DISABLED", "Account security feature is disabled",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
