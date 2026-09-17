package com.actilazion.aries_transaction.identity.smartotp.application;

import com.actilazion.aries_transaction.audit.application.IdentityAuditService;
import com.actilazion.aries_transaction.audit.domain.IdentityAuditEventType;
import com.actilazion.aries_transaction.identity.application.*;
import com.actilazion.aries_transaction.identity.smartotp.domain.*;
import com.actilazion.aries_transaction.identity.smartotp.infrastructure.SmartOtpRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SmartOtpVerificationService {
    private final SmartOtpChallengeService challenges;
    private final SmartOtpRepository repository;
    private final SessionRevocationService sessions;
    private final SmartOtpCrypto crypto;
    private final IdentityAuditService audit;

    public record Outcome(String error) {
        public void requireSuccess() {
            if ("SMART_OTP_RATE_LIMITED".equals(error))
                throw new com.actilazion.aries_transaction.common.exception.RateLimitExceededException(900);
            if (error != null) throw new SmartOtpException(error, HttpStatus.BAD_REQUEST);
        }
    }

    /** Returns rejection instead of throwing so durable failure counters commit before HTTP translation. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome verify(AuthenticatedUserPrincipal principal, UUID id, OtpPurpose purpose, String code) {
        challenges.requireEnabled();
        var user = sessions.lockAuthenticatedUser(principal);
        repository.ensureProfile(user.getId());
        var profile = repository.profile(user.getId());
        var now = challenges.now();
        if (profile.blockedUntil() != null && profile.blockedUntil().isAfter(now)) return new Outcome("SMART_OTP_RATE_LIMITED");
        // User lock serializes credential discovery with replacement/recovery and execute.
        var credentialId = repository.challengeCredential(id, user.getId()).orElseThrow(challenges::notFound);
        var credential = repository.credential(credentialId, user.getId()).orElseThrow(challenges::notFound);
        var c = repository.challenge(id, user.getId()).orElseThrow(challenges::notFound);
        challenges.requireUsable(user, credential, c, purpose);
        if (purpose == OtpPurpose.ENROLLMENT) {
            if (!"PENDING".equals(credential.state()) || !credential.expiresAt().isAfter(now)
                    || credential.authVersion() != user.getAuthVersion()) return new Outcome("SMART_OTP_EXPIRED");
        } else if (!"ACTIVE".equals(credential.state())) return new Outcome("SMART_OTP_DEVICE_REVOKED");
        if ("VERIFIED".equals(c.state())) return new Outcome(null);
        if (!"PENDING".equals(c.state())) return new Outcome("SMART_OTP_CONSUMED");
        byte[] secret = crypto.decrypt(user.getId(), credential.id(), credential.keyId(), credential.ciphertext());
        boolean matches;
        try { matches = Ocra.matches(secret, Base64.getDecoder().decode(c.payloadBase64()), code); }
        finally { Arrays.fill(secret, (byte) 0); }
        if (!matches) {
            repository.failed(user.getId(), id, now);
            audit.recordCommitted(IdentityAuditEventType.SMART_OTP_REJECTED, user.getId(), null, Map.of("challengeId", id.toString()));
            return new Outcome("SMART_OTP_INVALID");
        }
        repository.state(id, "VERIFIED");
        return new Outcome(null);
    }
}
