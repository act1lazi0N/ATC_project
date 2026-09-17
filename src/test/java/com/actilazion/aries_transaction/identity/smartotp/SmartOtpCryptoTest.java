package com.actilazion.aries_transaction.identity.smartotp;

import com.actilazion.aries_transaction.identity.smartotp.application.*;
import com.actilazion.aries_transaction.identity.smartotp.domain.SmartOtpException;
import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SmartOtpCryptoTest {
    @Test void encryptionUsesFreshNonceAndBindsOwnerCredentialAndKey() {
        var p = new SmartOtpProperties(); p.setKeyId("test-key");
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        p.setEncryptionKey(Base64.getEncoder().encodeToString(key));
        var crypto = new SmartOtpCrypto(p);
        var user = UUID.randomUUID(); var device = UUID.randomUUID(); var secret = crypto.randomBytes(32);
        var encrypted = crypto.encrypt(user, device, secret);
        assertThat(encrypted).isNotEqualTo(crypto.encrypt(user, device, secret));
        assertThat(crypto.decrypt(user, device, p.getKeyId(), encrypted)).containsExactly(secret);
        assertThatThrownBy(() -> crypto.decrypt(UUID.randomUUID(), device, p.getKeyId(), encrypted)).isInstanceOf(SmartOtpException.class);
        assertThatThrownBy(() -> crypto.decrypt(user, UUID.randomUUID(), p.getKeyId(), encrypted)).isInstanceOf(SmartOtpException.class);
        assertThatThrownBy(() -> crypto.decrypt(user, device, "other", encrypted)).isInstanceOf(SmartOtpException.class);
        byte[] damaged = Base64.getDecoder().decode(encrypted); damaged[20] ^= 1;
        assertThatThrownBy(() -> crypto.decrypt(user, device, p.getKeyId(), Base64.getEncoder().encodeToString(damaged))).isInstanceOf(SmartOtpException.class);
        p.setEncryptionKey("");
        assertThatThrownBy(() -> crypto.encrypt(user, device, secret)).isInstanceOf(SmartOtpException.class);
    }
}
