package com.actilazion.aries_transaction.notification.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.notification")
public class NotificationProperties {
    private boolean fanoutEnabled;
    private int retentionDays = 90;
    private int deliveryAttemptRetentionDays = 30;
    private boolean cleanupEnabled = true;
    private long cleanupIntervalMs = 3_600_000;
    private Email email = new Email();

    @Getter
    @Setter
    public static class Email {
        private String mode = "disabled";
        private boolean workerEnabled;
        private int batchSize = 25;
        private long pollIntervalMs = 5_000;
        private Duration processingLease = Duration.ofMinutes(5);
        private int maxAttempts = 8;
        private String from = "";
        private String publicBaseUrl = "";
        private String verificationSigningKey = "";
        private Duration verificationTtl = Duration.ofHours(24);
    }
}
