package com.actilazion.aries_transaction.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtConfig {
    private String secret;
    private long expiration = 900L;   // default 15 minutes
    private long refreshExpiration = 2592000L; // default 30 days
    private boolean refreshCookieSecure = true;
    private String refreshCookieSameSite = "Strict";
    private String issuer = "aries-transaction";
    private String audience = "aries-transaction-api";
    private String tokenType = "access";

}
