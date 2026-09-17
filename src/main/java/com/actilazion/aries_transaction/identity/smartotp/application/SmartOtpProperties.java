package com.actilazion.aries_transaction.identity.smartotp.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.smart-otp")
public class SmartOtpProperties {
    public enum Mode { DISABLED, ENROLLMENT_ONLY, ENFORCED }
    private Mode mode = Mode.DISABLED;
    private String keyId = "";
    private String encryptionKey = "";
}
