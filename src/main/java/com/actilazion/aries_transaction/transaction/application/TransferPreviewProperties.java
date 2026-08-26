package com.actilazion.aries_transaction.transaction.application;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "app.transfer.preview")
public class TransferPreviewProperties {
    @Min(1)
    private long ttlMinutes = 5;

    @Min(1)
    private long rateLimitWindowSeconds = 60;

    @Min(1)
    private int userRequests = 30;

    @Min(1)
    private int ipRequests = 120;

    @Min(0)
    private long minimumResponseMillis = 50;

    @Min(1)
    private long retentionHours = 24;

    @Min(1)
    private long cleanupFixedDelayMs = 3_600_000;
}
