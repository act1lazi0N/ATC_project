package com.actilazion.aries_transaction.payment.application;

import com.actilazion.aries_transaction.account.application.AccountPartyMasking;
import com.actilazion.aries_transaction.account.domain.*;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.audit.application.AuditLogService;
import com.actilazion.aries_transaction.audit.domain.AuditEventType;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.payment.domain.*;
import com.actilazion.aries_transaction.payment.dto.*;
import com.actilazion.aries_transaction.payment.infrastructure.PaymentQrRepository;
import com.actilazion.aries_transaction.transaction.domain.TransferAmountPolicy;
import com.actilazion.aries_transaction.transaction.domain.exception.IdempotencyConflictException;
import com.actilazion.aries_transaction.transaction.dto.TransferPreviewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentQrService {
    private final PaymentQrRepository qrRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public QrResponse create(UUID accountId, CreateQrRequest request, String key, UUID callerId) {
        if (key == null || key.isBlank() || key.length() > 64) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to 64 characters");
        }
        if (request == null || request.type() == null || !"VND".equals(request.currency())
                || (request.description() != null && request.description().length() > 255)) {
            throw new IllegalArgumentException("Invalid QR request");
        }
        if (request.type() == QrType.ACCOUNT && (request.amount() != null || request.description() != null)) {
            throw new IllegalArgumentException("Account QR cannot fix amount or description");
        }
        BigDecimal amount = request.type() == QrType.PAYMENT_REQUEST ? TransferAmountPolicy.normalize(request.amount()) : null;
        User owner = userRepository.findByIdWithLock(callerId).orElseThrow(QrException::unavailable);
        requireActive(owner);
        var existing = qrRepository.findByOwnerIdAndIdempotencyKey(callerId, key);
        if (existing.isPresent()) {
            PaymentQrCode qr = existing.get();
            if (!qr.getAccountId().equals(accountId) || qr.getType() != request.type()
                    || !Objects.equals(qr.getAmount(), amount) || !Objects.equals(qr.getDescription(), request.description())) {
                throw new IdempotencyConflictException(key);
            }
            return QrResponse.from(qr);
        }
        Account account = accountRepository.findByIdWithLock(accountId).orElseThrow(QrException::unavailable);
        requireOwner(account, callerId);
        requireReceivable(account);
        if (request.type() == QrType.ACCOUNT && qrRepository.existsByAccountIdAndTypeAndState(accountId, QrType.ACCOUNT, QrState.ACTIVE)) {
            throw new QrException("QR_ACCOUNT_EXISTS", "Account already has an active QR code", HttpStatus.CONFLICT);
        }
        PaymentQrCode qr = qrRepository.save(PaymentQrCode.create(callerId, accountId, request.type(), amount,
                request.description(), key, OffsetDateTime.now()));
        auditLogService.log(qr, AuditEventType.QR_CREATED, callerId.toString());
        return QrResponse.from(qr);
    }

    @Transactional(readOnly = true)
    public Page<QrResponse> list(UUID accountId, UUID callerId, int page, int size) {
        requireCaller(callerId);
        requireOwner(accountRepository.findById(accountId).orElseThrow(QrException::unavailable), callerId);
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page or size (maximum 100)");
        return qrRepository.findByAccountId(accountId, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt", "id"))).map(QrResponse::from);
    }

    @Transactional
    public QrResponse revoke(UUID qrId, UUID callerId) {
        // No account lock/read is needed here: QR owner/account bindings are immutable.
        requireActive(userRepository.findByIdWithLock(callerId).orElseThrow(QrException::unavailable));
        PaymentQrCode qr = qrRepository.findByIdWithLock(qrId).orElseThrow(QrException::unavailable);
        if (!qr.getOwnerId().equals(callerId)) throw QrException.unavailable();
        if (qr.revoke(OffsetDateTime.now())) auditLogService.log(qr, AuditEventType.QR_REVOKED, callerId.toString());
        return QrResponse.from(qr);
    }

    @Transactional(readOnly = true)
    public ResolvedQrResponse resolve(String payload, UUID callerId) {
        requireCaller(callerId);
        PaymentQrCode qr = available(QrPayload.parse(payload));
        Account recipient = accountRepository.findById(qr.getAccountId()).orElseThrow(QrException::unavailable);
        requireReceivable(recipient);
        return new ResolvedQrResponse(qr.getId(), qr.getType(), new TransferPreviewResponse.MaskedAccount(
                AccountPartyMasking.maskedNumber(recipient), AccountPartyMasking.safeDisplayName(recipient)),
                qr.getAmount() == null ? null : qr.getAmount().toPlainString(), qr.getCurrency(), qr.getDescription(), qr.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public PaymentQrCode available(UUID id) {
        PaymentQrCode qr = qrRepository.findById(id).orElseThrow(QrException::unavailable);
        qr.requirePayable(OffsetDateTime.now());
        return qr;
    }

    private void requireCaller(UUID callerId) {
        requireActive(userRepository.findById(callerId).orElseThrow(QrException::unavailable));
    }

    private void requireActive(User user) {
        if (!Boolean.TRUE.equals(user.getIsActive()) || (user.getLockedUntil() != null && user.getLockedUntil().isAfter(OffsetDateTime.now()))) {
            throw new AccessDeniedException("User is not active");
        }
    }

    private void requireOwner(Account account, UUID ownerId) {
        if (!account.getUser().getId().equals(ownerId)) throw QrException.unavailable();
    }

    private void requireReceivable(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE || !"VND".equals(account.getCurrency())
                || !Boolean.TRUE.equals(account.getUser().getIsActive())) throw QrException.unavailable();
    }
}
