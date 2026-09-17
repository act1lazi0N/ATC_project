package com.actilazion.aries_transaction.smartotp.application;

import com.actilazion.aries_transaction.account.application.AccountPartyMasking;
import com.actilazion.aries_transaction.identity.application.*;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.smartotp.domain.*;
import com.actilazion.aries_transaction.smartotp.dto.SmartOtpDtos.ChallengeView;
import com.actilazion.aries_transaction.smartotp.infrastructure.SmartOtpRepository;
import com.actilazion.aries_transaction.smartotp.infrastructure.SmartOtpRepository.*;
import com.actilazion.aries_transaction.transaction.domain.TransferPreview;
import com.actilazion.aries_transaction.transaction.infrastructure.TransferPreviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SmartOtpChallengeService {
    private final SmartOtpProperties properties;
    private final SmartOtpRepository repository;
    private final SessionRevocationService sessions;
    private final TransferPreviewRepository previews;
    private final SmartOtpCrypto crypto;
    private final ObjectMapper json;
    private final Clock clock;

    public void requireEnabled() {
        if (properties.getMode() == SmartOtpProperties.Mode.DISABLED) throw SmartOtpException.unavailable();
    }
    public boolean enforced() { return properties.getMode() == SmartOtpProperties.Mode.ENFORCED; }

    @Transactional
    public ChallengeView management(AuthenticatedUserPrincipal principal, OtpPurpose purpose) {
        requireEnabled();
        if (purpose == OtpPurpose.ENROLLMENT || purpose == OtpPurpose.TRANSFER)
            throw SmartOtpException.conflict("SMART_OTP_PURPOSE_INVALID");
        User user = sessions.lockAuthenticatedUser(principal);
        Credential credential = active(user.getId());
        return view(issue(user, credential, purpose, null, null, now().plusSeconds(120)));
    }

    @Transactional
    public ChallengeView authorize(AuthenticatedUserPrincipal principal, UUID previewId, String key) {
        requireEnabled();
        if (key == null || key.length() < 16 || key.length() > 64) throw new IllegalArgumentException("Invalid idempotency key");
        User user = sessions.lockAuthenticatedUser(principal);
        Credential credential = active(user.getId());
        TransferPreview preview = previews.findByIdWithLock(previewId).orElseThrow(this::notFound);
        if (!preview.getInitiator().getId().equals(user.getId())) throw notFound();
        Optional<Challenge> existing = repository.forPreview(previewId, user.getId());
        if (existing.isPresent()) {
            Challenge c = existing.get();
            if (!Objects.equals(c.idempotencyKey(), key) || !Objects.equals(c.bindingHash(), binding(preview, key)))
                throw SmartOtpException.conflict("SMART_OTP_BINDING_CONFLICT");
            return currentView(user, c);
        }
        if (preview.getConsumedAt() != null || !preview.getExpiresAt().isAfter(now()))
            throw SmartOtpException.conflict("SMART_OTP_EXPIRED");
        OffsetDateTime expires = now().plusSeconds(120);
        if (preview.getExpiresAt().isBefore(expires)) expires = preview.getExpiresAt();
        preview.setSmartOtpBound(true);
        // Flush JPA before inserting the JDBC foreign key, on the same transaction/connection.
        previews.flush();
        return view(issue(user, credential, OtpPurpose.TRANSFER, preview, key, expires));
    }

    @Transactional
    public ChallengeView read(AuthenticatedUserPrincipal principal, UUID id) {
        requireEnabled();
        User user = sessions.lockAuthenticatedUser(principal);
        return currentView(user, repository.challenge(id, user.getId()).orElseThrow(this::notFound));
    }

    public Credential active(UUID user) {
        return repository.active(user).orElseThrow(() -> SmartOtpException.conflict("SMART_OTP_ENROLLMENT_REQUIRED"));
    }

    public Challenge issue(User user, Credential credential, OtpPurpose purpose, TransferPreview preview,
                           String key, OffsetDateTime expires) {
        UUID id = UUID.randomUUID();
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("version", 1); payload.put("challengeId", id); payload.put("userId", user.getId());
        payload.put("deviceId", credential.id()); payload.put("authVersion", user.getAuthVersion());
        payload.put("purpose", purpose.name()); payload.put("nonce", crypto.token()); payload.put("expiresAt", expires.toString());
        if (preview != null) {
            payload.put("previewId", preview.getId()); payload.put("idempotencyKey", key);
            payload.put("recipient", AccountPartyMasking.safeDisplayName(preview.getDestinationAccount()));
            payload.put("accountNumberMasked", AccountPartyMasking.maskedNumber(preview.getDestinationAccount()));
            payload.put("amount", preview.getAmount().toPlainString()); payload.put("fee", preview.getFee().toPlainString());
            payload.put("debitTotal", preview.getAmount().add(preview.getFee()).toPlainString());
            payload.put("currency", preview.getCurrency()); payload.put("description", preview.getDescription());
        }
        String encoded = Base64.getEncoder().encodeToString(json.writeValueAsBytes(payload));
        Challenge challenge = new Challenge(id, user.getId(), credential.id(), purpose, user.getAuthVersion(),
                preview == null ? null : preview.getId(), key, preview == null ? null : binding(preview, key),
                encoded, "PENDING", 0, expires);
        repository.insert(challenge, now());
        return challenge;
    }

    public String binding(TransferPreview p, String key) {
        // Private destination/account identities bind authorization without exposing foreign UUIDs.
        return SmartOtpCrypto.hash(json.writeValueAsString(Arrays.asList(p.getId(), p.getInitiator().getId(),
                p.getSourceAccount().getId(), p.getDestinationAccount().getId(), p.getAmount().toPlainString(),
                p.getFee().toPlainString(), p.getCurrency(), p.getDescription(), p.getQrCodeId(), key)));
    }

    public void requireUsable(User user, Credential credential, Challenge c, OtpPurpose purpose) {
        if (c.purpose() != purpose || !c.credentialId().equals(credential.id()) || c.authVersion() != user.getAuthVersion())
            throw SmartOtpException.conflict("SMART_OTP_BINDING_CONFLICT");
        if (!c.expiresAt().isAfter(now())) throw SmartOtpException.conflict("SMART_OTP_EXPIRED");
        if (c.failedAttempts() >= 5) throw SmartOtpException.conflict("SMART_OTP_LOCKED");
        if ("REVOKED".equals(c.state()) || "REVOKED".equals(credential.state()))
            throw SmartOtpException.conflict("SMART_OTP_DEVICE_REVOKED");
    }

    public ChallengeView view(Challenge c) {
        String state = c.state();
        if ("PENDING".equals(state) || "VERIFIED".equals(state)) {
            if (!c.expiresAt().isAfter(now())) state = "EXPIRED";
            else if (c.failedAttempts() >= 5) state = "LOCKED";
        }
        return new ChallengeView(c.id(), c.credentialId(), c.purpose(), Ocra.SUITE, c.payloadBase64(), state, c.expiresAt());
    }
    private ChallengeView currentView(User user, Challenge c) {
        if (c.authVersion() != user.getAuthVersion() && !"CONSUMED".equals(c.state()))
            return new ChallengeView(c.id(), c.credentialId(), c.purpose(), Ocra.SUITE, c.payloadBase64(), "REVOKED", c.expiresAt());
        return view(c);
    }
    public SmartOtpException notFound() { return new SmartOtpException("SMART_OTP_NOT_FOUND", HttpStatus.NOT_FOUND); }
    public OffsetDateTime now() { return OffsetDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
}
