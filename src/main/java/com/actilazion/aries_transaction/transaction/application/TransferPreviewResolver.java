package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.payment.application.PaymentQrService;
import com.actilazion.aries_transaction.payment.domain.PaymentQrCode;
import com.actilazion.aries_transaction.payment.domain.QrType;
import com.actilazion.aries_transaction.transaction.domain.TransferPreviewMode;
import com.actilazion.aries_transaction.transaction.domain.exception.CurrencyMismatchException;
import com.actilazion.aries_transaction.transaction.domain.exception.RecipientUnavailableException;
import com.actilazion.aries_transaction.transaction.dto.TransferPreviewRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
public class TransferPreviewResolver {
    private final AccountRepository accountRepository;
    private final PaymentQrService paymentQrService;

    public ResolvedDestination resolve(TransferPreviewRequest request, User initiator) {
        if (request.qrCodeId() != null) {
            return qr(request, initiator);
        }
        if (request.mode() == TransferPreviewMode.EXTERNAL) {
            return external(request);
        }
        return ownAccount(request, initiator);
    }

    private ResolvedDestination qr(TransferPreviewRequest request, User initiator) {
        if (request.toAccountId() != null || request.recipientAccountNumber() != null) {
            throw new IllegalArgumentException("QR preview cannot contain another recipient selector");
        }
        PaymentQrCode qr = paymentQrService.available(request.qrCodeId());
        Account destination = accountRepository.findById(qr.getAccountId()).orElseThrow(RecipientUnavailableException::new);
        TransferPreviewMode mode = destination.getUser().getId().equals(initiator.getId())
                ? TransferPreviewMode.OWN_ACCOUNTS : TransferPreviewMode.EXTERNAL;
        if (request.mode() != null && request.mode() != mode) {
            throw new IllegalArgumentException("QR preview mode does not match account ownership");
        }
        if (request.currency() != null && !request.currency().equals(qr.getCurrency())) {
            throw new CurrencyMismatchException("QR currency cannot be overridden");
        }
        String amount = request.amount();
        String description = request.description();
        if (qr.getType() == QrType.PAYMENT_REQUEST) {
            if (amount != null || description != null) {
                throw new IllegalArgumentException("Payment request amount and description must be omitted");
            }
            amount = qr.getAmount().toPlainString();
            description = qr.getDescription();
        }
        if (!Boolean.TRUE.equals(destination.getUser().getIsActive())) {
            throw new RecipientUnavailableException();
        }
        return new ResolvedDestination(destination, mode, amount, qr.getCurrency(), description, qr.getExpiresAt());
    }

    private ResolvedDestination external(TransferPreviewRequest request) {
        if (request.toAccountId() != null || request.recipientAccountNumber() == null) {
            throw new IllegalArgumentException("External preview requires recipientAccountNumber only");
        }
        Account destination = accountRepository.findByAccountNumber(request.recipientAccountNumber())
                .orElseThrow(RecipientUnavailableException::new);
        if (destination.getStatus() != AccountStatus.ACTIVE) {
            throw new RecipientUnavailableException();
        }
        return new ResolvedDestination(destination, TransferPreviewMode.EXTERNAL, request.amount(),
                request.currency(), request.description(), null);
    }

    private ResolvedDestination ownAccount(TransferPreviewRequest request, User initiator) {
        if (request.mode() != TransferPreviewMode.OWN_ACCOUNTS) {
            throw new IllegalArgumentException("Manual preview requires mode");
        }
        if (request.toAccountId() == null || request.recipientAccountNumber() != null) {
            throw new IllegalArgumentException("Own-account preview requires toAccountId only");
        }
        Account destination = accountRepository.findById(request.toAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.toAccountId()));
        if (!destination.getUser().getId().equals(initiator.getId())) {
            throw new RecipientUnavailableException();
        }
        return new ResolvedDestination(destination, TransferPreviewMode.OWN_ACCOUNTS, request.amount(),
                request.currency(), request.description(), null);
    }

    public record ResolvedDestination(Account account, TransferPreviewMode mode, String amount,
                                      String currency, String description, OffsetDateTime expiresAt) {
    }
}
