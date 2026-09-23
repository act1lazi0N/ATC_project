package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.account.application.AccountPartyMasking;
import com.actilazion.aries_transaction.audit.application.AuditLogService;
import com.actilazion.aries_transaction.audit.domain.AuditEventType;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.common.exception.ForbiddenOperationException;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.transaction.domain.TransferPreview;
import com.actilazion.aries_transaction.transaction.domain.TransferAmountPolicy;
import com.actilazion.aries_transaction.transaction.domain.exception.CurrencyMismatchException;
import com.actilazion.aries_transaction.transaction.domain.exception.InsufficientBalanceException;
import com.actilazion.aries_transaction.transaction.domain.exception.RecipientUnavailableException;
import com.actilazion.aries_transaction.transaction.domain.exception.SelfTransferException;
import com.actilazion.aries_transaction.transaction.dto.TransferPreviewRequest;
import com.actilazion.aries_transaction.transaction.dto.TransferPreviewResponse;
import com.actilazion.aries_transaction.transaction.infrastructure.TransferPreviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TransferPreviewServiceImpl implements TransferPreviewService {
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final TransferPreviewRepository previewRepository;
    private final TransferPreviewProperties properties;
    private final AuditLogService auditLogService;
    private final TransferPreviewResolver destinationResolver;
    private final com.actilazion.aries_transaction.smartotp.application.SmartOtpTransferGuard smartOtp;

    @Override
    @Transactional
    public TransferPreviewResponse create(TransferPreviewRequest request, String initiatorEmail) {
        if (request.qrCodeId() == null && (request.mode() == null || request.amount() == null
                || request.amount().isBlank() || request.currency() == null || request.currency().isBlank())) {
            throw new IllegalArgumentException("Manual preview requires mode, amount and currency");
        }
        User initiator = userRepository.findByEmailWithLock(initiatorEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", initiatorEmail));
        if (!Boolean.TRUE.equals(initiator.getIsActive())) {
            throw new ForbiddenOperationException("User is not active");
        }
        Account source = accountRepository.findById(request.sourceAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.sourceAccountId()));
        if (!source.getUser().getId().equals(initiator.getId())) {
            throw new ForbiddenOperationException("Caller is not authorized for this source account");
        }
        var resolved = destinationResolver.resolve(request, initiator);
        Account destination = resolved.account();
        if (source.getId().equals(destination.getId())) {
            throw new SelfTransferException("Self transfer is not allowed");
        }
        validateActive(source);
        validateActive(destination);
        if (!source.getCurrency().equals(destination.getCurrency()) || !source.getCurrency().equals(resolved.currency())) {
            throw new CurrencyMismatchException("Transfer currency does not match both accounts");
        }
        if (!"VND".equals(resolved.currency())) {
            throw new CurrencyMismatchException("Unsupported currency");
        }
        BigDecimal amount = TransferAmountPolicy.normalize(resolved.amount());
        if (source.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException(source.getBalance(), amount);
        }
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(properties.getTtlMinutes());
        if (resolved.expiresAt() != null && resolved.expiresAt().isBefore(expiresAt)) {
            expiresAt = resolved.expiresAt();
        }
        TransferPreview preview = previewRepository.save(TransferPreview.builder()
                .initiator(initiator)
                .sourceAccount(source)
                .destinationAccount(destination)
                .mode(resolved.mode())
                .qrCodeId(request.qrCodeId())
                .amount(amount)
                .fee(BigDecimal.ZERO.setScale(2))
                .currency(resolved.currency())
                .description(resolved.description())
                .expiresAt(expiresAt)
                .build());
        auditLogService.log(preview, AuditEventType.TRANSFER_PREVIEW_CREATED, initiatorEmail);
        return response(preview);
    }

    private void validateActive(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) throw new RecipientUnavailableException();
    }

    private TransferPreviewResponse response(TransferPreview preview) {
        return new TransferPreviewResponse(preview.getId(), preview.getExpiresAt(),
                masked(preview.getSourceAccount()), masked(preview.getDestinationAccount()),
                preview.getAmount().toPlainString(), preview.getFee().toPlainString(),
                preview.getAmount().add(preview.getFee()).toPlainString(), preview.getCurrency(), List.of(),
                smartOtp.required(preview.getSourceAccount(), preview.getDestinationAccount()) ? "SMART_OTP" : "NONE",
                smartOtp.enrollmentState(preview.getInitiator().getId()));
    }

    private TransferPreviewResponse.MaskedAccount masked(Account account) {
        return new TransferPreviewResponse.MaskedAccount(
                AccountPartyMasking.maskedNumber(account),
                AccountPartyMasking.safeDisplayName(account));
    }
}
