package com.actilazion.aries_transaction.config;

import com.actilazion.aries_transaction.identity.application.AccountSecurityProperties;
import com.actilazion.aries_transaction.identity.application.PasswordResetTokenService;
import com.actilazion.aries_transaction.support.TestSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class AccountSecurityConfigurationTest {
    @Test
    void disabledFeatureDoesNotRequireSecretsAndEnabledFeatureRejectsUnsafeConfiguration() throws Exception {
        var properties = new AccountSecurityProperties();
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var validator = new AccountSecurityConfiguration().accountSecurityValidation(properties,
                new PasswordResetTokenService(properties), environment);
        validator.afterPropertiesSet();
        properties.setEnabled(true);
        assertThatThrownBy(validator::afterPropertiesSet).hasMessageContaining("256 bits");
        properties.setResetSigningKey(TestSecrets.newBase64Key());
        for (String url : new String[]{"http://app.test/reset", "https://user@app.test/reset", "https://app.test/reset?next=x", "/reset"}) {
            properties.setResetPublicUrl(url);
            assertThatThrownBy(validator::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
        }
        properties.setResetPublicUrl("https://app.test/reset-password");
        validator.afterPropertiesSet();
        properties.setResetTtl(Duration.ZERO);
        assertThatThrownBy(validator::afterPropertiesSet).hasMessageContaining("TTL");
    }
}
