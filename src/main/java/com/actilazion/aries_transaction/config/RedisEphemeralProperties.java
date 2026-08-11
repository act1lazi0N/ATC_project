package com.actilazion.aries_transaction.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "security.ephemeral")
public class RedisEphemeralProperties {
    private boolean enabled = true;

    @NotBlank
    private String keyPrefix = "aries:transaction:dev:v1";

    @NotBlank
    private String keyHashSecret;

    @Min(50)
    private long commandTimeoutMs = 250;
}
