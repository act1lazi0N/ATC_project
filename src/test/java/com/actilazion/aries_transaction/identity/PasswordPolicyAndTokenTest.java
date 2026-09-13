package com.actilazion.aries_transaction.identity;

import com.actilazion.aries_transaction.identity.application.*;
import com.actilazion.aries_transaction.identity.domain.PasswordResetChallenge;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.dto.*;
import com.actilazion.aries_transaction.support.TestSecrets;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class PasswordPolicyAndTokenTest {
    @Test
    void passwordPolicyEnforcesUtf8LimitWithoutTrimming() {
        assertThatCode(() -> PasswordPolicy.validateNew("é".repeat(36))).doesNotThrowAnyException();
        assertThatCode(() -> PasswordPolicy.validateNew(" padded password ")).doesNotThrowAnyException();
        for (String password : new String[]{"short", "é".repeat(37), " ".repeat(10), "a".repeat(73), null}) {
            assertThatThrownBy(() -> PasswordPolicy.validateNew(password)).hasMessageContaining("72 UTF-8 bytes");
        }
    }

    @Test
    void resetTokenIsBoundToChallengeUserEmailExpiryAndIndependentKey() {
        AccountSecurityProperties properties = new AccountSecurityProperties();
        properties.setResetSigningKey(TestSecrets.newBase64Key());
        PasswordResetTokenService tokens = new PasswordResetTokenService(properties);
        OffsetDateTime now = OffsetDateTime.now();
        User user = User.builder().id(UUID.randomUUID()).email("owner@test.local").build();
        PasswordResetChallenge challenge = PasswordResetChallenge.builder().id(UUID.randomUUID())
                .user(user).email(user.getEmail()).createdAt(now).expiresAt(now.plusMinutes(15)).build();
        String token = tokens.tokenFor(challenge);
        assertThat(tokens.matches(token, challenge)).isTrue();
        assertThat(challenge.isUsableAt(challenge.getExpiresAt())).isFalse();
        assertThat(challenge.isUsableAt(challenge.getExpiresAt().minusNanos(1))).isTrue();
        challenge.setEmail("other@test.local");
        assertThat(tokens.matches(token, challenge)).isFalse();
        challenge.setEmail(user.getEmail());
        challenge.setExpiresAt(now.plusMinutes(16));
        assertThat(tokens.matches(token, challenge)).isFalse();
        challenge.setExpiresAt(now.plusMinutes(15));
        user.setId(UUID.randomUUID());
        assertThat(tokens.matches(token, challenge)).isFalse();
        String currentToken = tokens.tokenFor(challenge);
        properties.setResetSigningKey(TestSecrets.newBase64Key());
        assertThat(tokens.matches(currentToken, challenge)).isFalse();
    }

    @Test
    void malformedTokensAndWeakKeysAreRejectedWithoutEchoingCredentials() {
        var properties = new AccountSecurityProperties();
        var tokens = new PasswordResetTokenService(properties);
        for (String token : new String[]{null, "", "secret-value", "a".repeat(5000)}) {
            assertThatThrownBy(() -> tokens.challengeId(token)).hasMessage("Invalid password reset token");
        }
        properties.setResetSigningKey("not-a-base64-secret");
        assertThatThrownBy(tokens::requireConfigured).hasMessage("PASSWORD_RESET_SIGNING_KEY must be valid Base64");
        properties.setResetSigningKey("YWJj");
        assertThatThrownBy(tokens::requireConfigured).hasMessageContaining("256 bits");
        assertThat(new ChangePasswordRequest("old-secret", "new-secret").toString()).doesNotContain("old-secret", "new-secret");
        assertThat(new ResetPasswordRequest("raw-token", "new-secret").toString()).doesNotContain("raw-token", "new-secret");
        assertThat(new ForgotPasswordRequest("private@test.local").toString()).doesNotContain("private@test.local");
    }
}
