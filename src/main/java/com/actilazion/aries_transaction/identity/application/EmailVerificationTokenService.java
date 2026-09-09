package com.actilazion.aries_transaction.identity.application;

import com.actilazion.aries_transaction.identity.domain.EmailVerificationChallenge;
import com.actilazion.aries_transaction.notification.application.NotificationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

@Component
public class EmailVerificationTokenService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final NotificationProperties properties;

    public EmailVerificationTokenService(NotificationProperties properties) {
        this.properties = properties;
    }

    public String tokenFor(EmailVerificationChallenge challenge) {
        return challenge.getId() + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signature(challenge));
    }

    public UUID challengeId(String token) {
        if (token == null || token.isBlank() || token.length() > 200) {
            throw new IllegalArgumentException("Invalid email verification token");
        }
        int separator = token.indexOf('.');
        if (separator <= 0 || separator != token.lastIndexOf('.')) {
            throw new IllegalArgumentException("Invalid email verification token");
        }
        return UUID.fromString(token.substring(0, separator));
    }

    public boolean matches(String token, EmailVerificationChallenge challenge) {
        try {
            int separator = token.indexOf('.');
            byte[] supplied = Base64.getUrlDecoder().decode(token.substring(separator + 1));
            return MessageDigest.isEqual(supplied, signature(challenge));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public void requireConfigured() {
        key();
    }

    private byte[] signature(EmailVerificationChallenge challenge) {
        String value = challenge.getId() + "|" + challenge.getUser().getId() + "|"
                + challenge.getUser().getEmail() + "|" + challenge.getExpiresAt();
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key(), HMAC_ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Email verification signing is unavailable", ex);
        }
    }

    private byte[] key() {
        try {
            byte[] decoded = Base64.getDecoder().decode(properties.getEmail().getVerificationSigningKey());
            if (decoded.length < 32) {
                throw new IllegalStateException("EMAIL_VERIFICATION_SIGNING_KEY must contain at least 256 bits");
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("EMAIL_VERIFICATION_SIGNING_KEY must be valid Base64", ex);
        }
    }
}
