package com.actilazion.aries_transaction.identity.application;

import com.actilazion.aries_transaction.identity.domain.PasswordResetChallenge;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PasswordResetTokenService {
    private final AccountSecurityProperties properties;

    public String tokenFor(PasswordResetChallenge challenge) {
        return challenge.getId() + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signature(challenge));
    }

    public UUID challengeId(String token) {
        if (token == null || !token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid password reset token");
        }
        return UUID.fromString(token.substring(0, 36));
    }

    public boolean matches(String token, PasswordResetChallenge challenge) {
        try {
            if (!challengeId(token).equals(challenge.getId())) {
                return false;
            }
            // Compare the canonical encoded token as well; reject alternate Base64 tail bits.
            return MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),
                    tokenFor(challenge).getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public void requireConfigured() {
        key();
    }

    private byte[] signature(PasswordResetChallenge challenge) {
        String content = "PASSWORD_RESET|" + challenge.getId() + "|" + challenge.getUser().getId()
                + "|" + challenge.getEmail() + "|" + challenge.getExpiresAt().toEpochSecond();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Password reset signing is unavailable", ex);
        }
    }

    private byte[] key() {
        try {
            byte[] key = Base64.getDecoder().decode(properties.getResetSigningKey());
            if (key.length < 32) {
                throw new IllegalStateException("PASSWORD_RESET_SIGNING_KEY must contain at least 256 bits");
            }
            return key;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("PASSWORD_RESET_SIGNING_KEY must be valid Base64");
        }
    }
}
