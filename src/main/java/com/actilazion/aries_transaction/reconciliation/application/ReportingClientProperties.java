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
@ConfigurationProperties(prefix = "app.reconciliation.reporting")
public class ReportingClientProperties {
    private ReportingClientMode mode = ReportingClientMode.DISABLED;
    private String baseUrl;
    private String snapshotsPath = "/api/v1/reporting/transactions/snapshots";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(5);

    @PostConstruct
    void validate() {
        if (mode == ReportingClientMode.HTTP && (baseUrl == null || baseUrl.isBlank())) {
            throw new IllegalStateException("app.reconciliation.reporting.base-url is required when mode=http");
        }
        if (snapshotsPath == null || snapshotsPath.isBlank() || !snapshotsPath.startsWith("/")) {
            throw new IllegalStateException("app.reconciliation.reporting.snapshots-path must start with /");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalStateException("reporting HTTP timeouts must be positive");
        }
    }

    public enum ReportingClientMode {
        DISABLED,
        HTTP,
        NOOP
    }
}
