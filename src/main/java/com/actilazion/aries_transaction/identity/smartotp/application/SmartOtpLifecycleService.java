package com.actilazion.aries_transaction.identity.smartotp.application;

import com.actilazion.aries_transaction.audit.application.IdentityAuditService;
import com.actilazion.aries_transaction.audit.domain.IdentityAuditEventType;
import com.actilazion.aries_transaction.identity.application.*;
import com.actilazion.aries_transaction.identity.domain.*;
import com.actilazion.aries_transaction.identity.smartotp.domain.*;
import com.actilazion.aries_transaction.identity.smartotp.dto.SmartOtpDtos.*;
import com.actilazion.aries_transaction.identity.smartotp.infrastructure.SmartOtpRepository;
import com.actilazion.aries_transaction.identity.smartotp.infrastructure.SmartOtpRepository.*;
import com.actilazion.aries_transaction.notification.domain.*;
import com.actilazion.aries_transaction.notification.infrastructure.EmailDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SmartOtpLifecycleService {
    private final SmartOtpProperties properties;
    private final SmartOtpChallengeService challenges;
    private final SmartOtpRepository repository;
    private final SessionRevocationService sessions;
    private final SmartOtpCrypto crypto;
    private final PasswordEncoder passwords;
    private final IdentityAuditService audit;
    private final EmailDeliveryRepository deliveries;

    @Transactional
    public Status status(AuthenticatedUserPrincipal principal) {
        User user = sessions.lockAuthenticatedUser(principal);
        if (properties.getMode() == SmartOtpProperties.Mode.DISABLED) return new Status("DISABLED", "UNAVAILABLE", null);
        var credential = repository.active(user.getId());
        return new Status(properties.getMode().name(), credential.isPresent() ? "ACTIVE" :
                repository.profile(user.getId()).everEnrolled() ? "RECOVERY_REQUIRED" : "NOT_ENROLLED",
                credential.map(Credential::id).orElse(null));
    }

    @Transactional
    public Enrollment enroll(AuthenticatedUserPrincipal principal, String password, UUID authorization, String grant) {
        challenges.requireEnabled();
        User user = sessions.lockAuthenticatedUser(principal);
        requirePassword(user, password);
        if (user.getEmailVerifiedAt() == null) throw SmartOtpException.conflict("EMAIL_VERIFICATION_REQUIRED");
        repository.ensureProfile(user.getId());
        Profile profile = repository.profile(user.getId());
        var active = repository.active(user.getId());
        if (active.isPresent()) consume(user, active.get(), authorization, OtpPurpose.REPLACE_DEVICE);
        else if (profile.everEnrolled()) {
            if (grant == null || !SmartOtpCrypto.hash(grant).equals(profile.grantHash())
                    || profile.grantExpiresAt() == null || !profile.grantExpiresAt().isAfter(challenges.now())
                    || !Objects.equals(profile.grantAuthVersion(), user.getAuthVersion()))
                throw SmartOtpException.conflict("SMART_OTP_RECOVERY_REQUIRED");
            repository.clearGrant(user.getId());
        }
        repository.revoke(user.getId(), "PENDING");
        UUID id = UUID.randomUUID();
        byte[] secret = crypto.randomBytes(32);
        try {
            var credential = new Credential(id, user.getId(), "PENDING", crypto.encrypt(user.getId(), id, secret),
                    properties.getKeyId(), user.getAuthVersion(), challenges.now().plusMinutes(10));
            repository.insert(credential, challenges.now());
            var proof = challenges.issue(user, credential, OtpPurpose.ENROLLMENT, null, null, credential.expiresAt());
            return new Enrollment(id, Base64.getEncoder().encodeToString(secret), challenges.view(proof));
        } finally { Arrays.fill(secret, (byte) 0); }
    }

    @Transactional
    public RecoveryCodes confirm(AuthenticatedUserPrincipal principal, UUID enrollment, UUID proof) {
        challenges.requireEnabled();
        User user = sessions.lockAuthenticatedUser(principal);
        var credential = repository.credential(enrollment, user.getId()).orElseThrow(challenges::notFound);
        if (!"PENDING".equals(credential.state()) || !credential.expiresAt().isAfter(challenges.now())
                || credential.authVersion() != user.getAuthVersion() || user.getEmailVerifiedAt() == null)
            throw SmartOtpException.conflict("SMART_OTP_EXPIRED");
        consume(user, credential, proof, OtpPurpose.ENROLLMENT);
        boolean replacement = repository.active(user.getId()).isPresent();
        repository.revoke(user.getId(), "ACTIVE");
        repository.activate(credential);
        repository.clearGrant(user.getId());
        notify(user, replacement ? "REPLACED" : "ENROLLED");
        return codes(user);
    }

    @Transactional
    public void revoke(AuthenticatedUserPrincipal principal, UUID device, String password, UUID proof) {
        challenges.requireEnabled();
        User user = sessions.lockAuthenticatedUser(principal);
        requirePassword(user, password);
        var credential = challenges.active(user.getId());
        if (!credential.id().equals(device)) throw challenges.notFound();
        consume(user, credential, proof, OtpPurpose.REVOKE_DEVICE);
        repository.revoke(user.getId(), "ACTIVE"); repository.revoke(user.getId(), "PENDING");
        repository.clearGrant(user.getId());
        notify(user, "REVOKED");
    }

    @Transactional
    public RecoveryCodes regenerate(AuthenticatedUserPrincipal principal, String password, UUID proof) {
        challenges.requireEnabled();
        User user = sessions.lockAuthenticatedUser(principal);
        requirePassword(user, password);
        consume(user, challenges.active(user.getId()), proof, OtpPurpose.REGENERATE_RECOVERY_CODES);
        notify(user, "RECOVERY_CODES_REGENERATED");
        return codes(user);
    }

    @Transactional
    public RecoveryGrant recover(AuthenticatedUserPrincipal principal, String password, String code) {
        challenges.requireEnabled();
        User user = sessions.lockAuthenticatedUser(principal);
        requirePassword(user, password);
        if (user.getEmailVerifiedAt() == null || code == null
                || !repository.consumeCode(user.getId(), SmartOtpCrypto.hash(code), challenges.now()))
            throw new SmartOtpException("SMART_OTP_RECOVERY_INVALID", HttpStatus.BAD_REQUEST);
        repository.revoke(user.getId(), "ACTIVE"); repository.revoke(user.getId(), "PENDING");
        sessions.revokeAll(user, RefreshSessionRevocationReason.SMART_OTP_RECOVERY, challenges.now());
        String grant = crypto.token(); var expires = challenges.now().plusMinutes(10);
        repository.grant(user.getId(), SmartOtpCrypto.hash(grant), expires, user.getAuthVersion());
        notify(user, "RECOVERED");
        return new RecoveryGrant(grant, expires);
    }

    private void consume(User user, Credential credential, UUID id, OtpPurpose purpose) {
        if (id == null) throw SmartOtpException.conflict("SMART_OTP_AUTHORIZATION_REQUIRED");
        var c = repository.challenge(id, user.getId()).orElseThrow(challenges::notFound);
        challenges.requireUsable(user, credential, c, purpose);
        if (!"VERIFIED".equals(c.state())) throw SmartOtpException.conflict("SMART_OTP_AUTHORIZATION_REQUIRED");
        repository.state(id, "CONSUMED");
    }
    private RecoveryCodes codes(User user) {
        List<String> codes = java.util.stream.IntStream.range(0, 10).mapToObj(i -> crypto.token()).toList();
        repository.replaceCodes(user.getId(), codes.stream().map(SmartOtpCrypto::hash).toList());
        return new RecoveryCodes(codes);
    }
    private void requirePassword(User user, String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(password, user.getPasswordHash()))
            throw new SmartOtpException("CURRENT_PASSWORD_INVALID", HttpStatus.BAD_REQUEST);
    }
    private void notify(User user, String action) {
        var event = audit.recordCommitted(IdentityAuditEventType.SMART_OTP_SECURITY, user.getId(), null, Map.of("action", action));
        deliveries.save(EmailDelivery.builder().id(UUID.randomUUID()).purpose(EmailDeliveryPurpose.SMART_OTP_SECURITY)
                .securityAuditEvent(event).build());
    }
}
