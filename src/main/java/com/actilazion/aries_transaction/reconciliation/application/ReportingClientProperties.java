package com.actilazion.aries_transaction.reconciliation.application;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.reconciliation.reporting")
public class ReportingClientProperties {
    private ReportingClientMode mode = ReportingClientMode.DISABLED;
    private String baseUrl;
    private String snapshotsPath = "/api/v1/reporting/transactions/snapshots";

    @PostConstruct
    void validate() {
        if (mode == ReportingClientMode.HTTP && (baseUrl == null || baseUrl.isBlank())) {
            throw new IllegalStateException("app.reconciliation.reporting.base-url is required when mode=http");
        }
        if (snapshotsPath == null || snapshotsPath.isBlank() || !snapshotsPath.startsWith("/")) {
            throw new IllegalStateException("app.reconciliation.reporting.snapshots-path must start with /");
        }
    }

    public enum ReportingClientMode {
        DISABLED,
        HTTP,
        NOOP
    }
}
