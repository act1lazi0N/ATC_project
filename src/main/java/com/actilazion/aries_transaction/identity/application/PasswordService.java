package com.actilazion.aries_transaction.identity.application;

import com.actilazion.aries_transaction.audit.application.IdentityAuditService;
import com.actilazion.aries_transaction.audit.domain.IdentityAuditEventType;
import com.actilazion.aries_transaction.identity.domain.PasswordResetChallenge;
import com.actilazion.aries_transaction.identity.domain.RefreshSessionRevocationReason;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.domain.exception.AccountSecurityException;
import com.actilazion.aries_transaction.identity.infrastructure.PasswordResetChallengeRepository;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.notification.domain.EmailDelivery;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryPurpose;
import com.actilazion.aries_transaction.notification.infrastructure.EmailDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordService {
    private final AccountSecurityProperties properties;
    private final PasswordResetTokenService tokens;
    private final UserRepository users;
    private final PasswordResetChallengeRepository challenges;
    private final EmailDeliveryRepository deliveries;
    private final PasswordEncoder passwords;
    private final SessionRevocationService sessions;
    private final IdentityAuditService audit;
    private final LoginAttemptStore loginAttempts;
    private final Clock clock;

    @Transactional
    public void requestReset(String email, String ipAddress) {
        properties.requireEnabled();
        tokens.requireConfigured();
        User user = users.findByEmailWithLock(email.trim().toLowerCase(Locale.ROOT)).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        challenges.invalidateActiveByUserId(user.getId(), now);
        PasswordResetChallenge challenge = challenges.saveAndFlush(PasswordResetChallenge.builder()
                .id(UUID.randomUUID()).user(user).email(user.getEmail())
                .createdAt(now).expiresAt(now.plus(properties.getResetTtl())).build());
        deliveries.save(EmailDelivery.builder().id(UUID.randomUUID())
                .purpose(EmailDeliveryPurpose.PASSWORD_RESET).passwordResetChallenge(challenge).build());
        audit.recordCommitted(IdentityAuditEventType.PASSWORD_RESET_REQUESTED, user.getId(), ipAddress,
                Map.of("challengeId", challenge.getId().toString()));
    }

    @Transactional
    public void changePassword(AuthenticatedUserPrincipal principal, String currentPassword,
                               String newPassword, String ipAddress) {
        properties.requireEnabled();
        User user = sessions.lockAuthenticatedUser(principal);
        if (currentPassword == null || currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(currentPassword, user.getPasswordHash())) {
            audit.record(IdentityAuditEventType.PASSWORD_CHANGE_REJECTED, user.getId(), null, ipAddress, Map.of());
            throw new AccountSecurityException("CURRENT_PASSWORD_INVALID", "Current password is incorrect",
                    HttpStatus.BAD_REQUEST);
        }
        change(user, newPassword, false, ipAddress, OffsetDateTime.now(clock));
    }

    @Transactional
    public void resetPassword(String token, String newPassword, String ipAddress) {
        properties.requireEnabled();
        UUID challengeId;
        try {
            challengeId = tokens.challengeId(token);
        } catch (IllegalArgumentException ex) {
            throw rejectReset(ipAddress);
        }
        // Scalar discovery, user lock, then challenge lock: shared order with issuance and revocation.
        UUID userId = challenges.findUserId(challengeId).orElseThrow(() -> rejectReset(ipAddress));
        User user = users.findByIdWithLock(userId).orElseThrow(() -> rejectReset(ipAddress));
        PasswordResetChallenge challenge = challenges.findByIdForUpdate(challengeId)
                .orElseThrow(() -> rejectReset(ipAddress));
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!challenge.isUsableAt(now) || !tokens.matches(token, challenge)) {
            throw rejectReset(ipAddress);
        }
        challenge.setConsumedAt(now);
        change(user, newPassword, true, ipAddress, now);
    }

    @Transactional
    public void logoutAll(AuthenticatedUserPrincipal principal, String ipAddress) {
        properties.requireEnabled();
        User user = sessions.lockAuthenticatedUser(principal);
        sessions.revokeAll(user, RefreshSessionRevocationReason.LOGOUT_ALL, OffsetDateTime.now(clock));
        audit.recordCommitted(IdentityAuditEventType.LOGOUT_ALL, user.getId(), ipAddress, Map.of());
    }

    private void change(User user, String newPassword, boolean reset, String ipAddress, OffsetDateTime now) {
        PasswordPolicy.validateNew(newPassword);
        if (passwords.matches(newPassword, user.getPasswordHash())) {
            throw new AccountSecurityException("PASSWORD_UNCHANGED", "New password must differ from current password",
                    HttpStatus.BAD_REQUEST);
        }
        if (reset) {
            loginAttempts.ensureAvailable();
            loginAttempts.clear("user:" + user.getId());
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
        }
        user.setPasswordHash(passwords.encode(newPassword));
        challenges.invalidateActiveByUserId(user.getId(), now);
        sessions.revokeAll(user, reset ? RefreshSessionRevocationReason.PASSWORD_RESET
                : RefreshSessionRevocationReason.PASSWORD_CHANGED, now);
        var event = audit.recordCommitted(reset ? IdentityAuditEventType.PASSWORD_RESET_COMPLETED
                : IdentityAuditEventType.PASSWORD_CHANGED, user.getId(), ipAddress, Map.of());
        deliveries.save(EmailDelivery.builder().id(UUID.randomUUID())
                .purpose(EmailDeliveryPurpose.PASSWORD_CHANGED).securityAuditEvent(event).build());
    }

    private AccountSecurityException rejectReset(String ipAddress) {
        audit.record(IdentityAuditEventType.PASSWORD_RESET_REJECTED, null, null, ipAddress, Map.of());
        return AccountSecurityException.invalidResetToken();
    }
}
