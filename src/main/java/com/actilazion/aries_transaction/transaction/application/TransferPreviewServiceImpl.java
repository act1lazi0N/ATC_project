package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.common.exception.ForbiddenOperationException;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.transaction.domain.TransferPreview;
import com.actilazion.aries_transaction.transaction.domain.TransferPreviewMode;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TransferPreviewServiceImpl implements TransferPreviewService {
    private static final int PREVIEW_TTL_MINUTES = 5;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final TransferPreviewRepository previewRepository;

    @Override
    @Transactional
    public TransferPreviewResponse create(TransferPreviewRequest request, String initiatorEmail) {
        User initiator = userRepository.findByEmail(initiatorEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", initiatorEmail));
        Account source = accountRepository.findById(request.sourceAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.sourceAccountId()));
        if (!source.getUser().getId().equals(initiator.getId())) {
            throw new ForbiddenOperationException("Caller is not authorized for this source account");
        }
        Account destination;
        if (request.mode() == TransferPreviewMode.EXTERNAL) {
            if (request.toAccountId() != null || request.recipientAccountNumber() == null) {
                throw new IllegalArgumentException("External preview requires recipientAccountNumber only");
            }
            destination = accountRepository.findByAccountNumber(request.recipientAccountNumber())
                    .orElseThrow(RecipientUnavailableException::new);
            if (destination.getStatus() != AccountStatus.ACTIVE) {
                throw new RecipientUnavailableException();
            }
        } else {
            if (request.toAccountId() == null || request.recipientAccountNumber() != null) {
                throw new IllegalArgumentException("Own-account preview requires toAccountId only");
            }
            destination = accountRepository.findById(request.toAccountId())
                    .orElseThrow(() -> new ResourceNotFoundException("Account", request.toAccountId()));
            if (!destination.getUser().getId().equals(initiator.getId())) {
                throw new RecipientUnavailableException();
            }
        }
        if (source.getId().equals(destination.getId())) {
            throw new SelfTransferException("Self transfer is not allowed");
        }
        validateActive(source);
        validateActive(destination);
        if (!source.getCurrency().equals(destination.getCurrency()) || !source.getCurrency().equals(request.currency())) {
            throw new CurrencyMismatchException("Transfer currency does not match both accounts");
        }
        if (!"VND".equals(request.currency())) {
            throw new CurrencyMismatchException("Unsupported currency");
        }
        BigDecimal amount = normalizeAmount(request.amount());
        if (source.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException(source.getBalance(), amount);
        }
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(PREVIEW_TTL_MINUTES);
        TransferPreview preview = previewRepository.save(TransferPreview.builder()
                .initiator(initiator)
                .sourceAccount(source)
                .destinationAccount(destination)
                .mode(request.mode())
                .amount(amount)
                .fee(BigDecimal.ZERO.setScale(2))
                .currency(request.currency())
                .description(request.description())
                .requestFingerprint(fingerprint(request, amount))
                .expiresAt(expiresAt)
                .build());
        return response(preview);
    }

    private BigDecimal normalizeAmount(String raw) {
        try {
            BigDecimal amount = new BigDecimal(raw).setScale(2);
            if (amount.signum() <= 0 || amount.scale() > 2) throw new IllegalArgumentException("Invalid amount");
            return amount;
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid amount");
        }
    }

    private void validateActive(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) throw new RecipientUnavailableException();
    }

    private String fingerprint(TransferPreviewRequest request, BigDecimal amount) {
        String value = String.join("|", request.mode().name(), request.sourceAccountId().toString(),
                request.toAccountId() == null ? "" : request.toAccountId().toString(),
                request.recipientAccountNumber() == null ? "" : request.recipientAccountNumber(),
                amount.toPlainString(), request.currency(), request.description() == null ? "" : request.description());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private TransferPreviewResponse response(TransferPreview preview) {
        return new TransferPreviewResponse(preview.getId(), preview.getExpiresAt(),
                masked(preview.getSourceAccount()), masked(preview.getDestinationAccount()),
                preview.getAmount().toPlainString(), preview.getFee().toPlainString(),
                preview.getAmount().add(preview.getFee()).toPlainString(), preview.getCurrency(), List.of());
    }

    private TransferPreviewResponse.MaskedAccount masked(Account account) {
        String number = account.getAccountNumber();
        String masked = "********" + number.substring(Math.max(0, number.length() - 4));
        String name = account.getUser().getFullName();
        String display = name == null || name.length() <= 1 ? "***" : name.charAt(0) + "***";
        return new TransferPreviewResponse.MaskedAccount(masked, display);
    }
}
