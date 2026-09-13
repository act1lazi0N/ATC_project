package com.actilazion.aries_transaction.config;

import com.actilazion.aries_transaction.identity.application.AccountSecurityProperties;
import com.actilazion.aries_transaction.identity.application.PasswordResetTokenService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.net.URI;
import java.time.Clock;

@Configuration
public class AccountSecurityConfiguration {
    @Bean
    public Clock accountSecurityClock() {
        return Clock.systemUTC();
    }

    @Bean
    InitializingBean accountSecurityValidation(AccountSecurityProperties properties,
                                               PasswordResetTokenService tokens, Environment environment) {
        return () -> {
            if (!properties.isEnabled()) {
                return;
            }
            tokens.requireConfigured();
            URI url = URI.create(properties.getResetPublicUrl());
            boolean production = environment.acceptsProfiles(Profiles.of("prod"));
            if (url.getHost() == null || url.getUserInfo() != null || url.getFragment() != null
                    || url.getQuery() != null || !("https".equals(url.getScheme())
                    || (!production && "http".equals(url.getScheme())))) {
                throw new IllegalStateException("PASSWORD_RESET_PUBLIC_URL must be a trusted absolute URL; production requires HTTPS");
            }
            if (properties.getResetTtl() == null || properties.getResetTtl().isNegative()
                    || properties.getResetTtl().isZero()) {
                throw new IllegalStateException("Password reset TTL must be positive");
            }
        };
    }
}
