package com.actilazion.aries_transaction.reconciliation.application;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.reconciliation")
public class ReconciliationPolicyProperties {
    private Duration expectedLag = Duration.ofMinutes(5);
    private Duration maxWindow = Duration.ofDays(31);

    @PostConstruct
    void validate() {
        if (expectedLag.isNegative()) {
            throw new IllegalStateException("app.reconciliation.expected-lag must not be negative");
        }
        if (maxWindow.isZero() || maxWindow.isNegative()) {
            throw new IllegalStateException("app.reconciliation.max-window must be positive");
        }
    }
}
