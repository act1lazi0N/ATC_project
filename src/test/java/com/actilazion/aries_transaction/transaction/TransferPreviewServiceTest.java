package com.actilazion.aries_transaction.transaction;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.audit.application.AuditLogService;
import com.actilazion.aries_transaction.transaction.application.TransferPreviewServiceImpl;
import com.actilazion.aries_transaction.transaction.application.TransferPreviewProperties;
import com.actilazion.aries_transaction.transaction.application.TransferPreviewResolver;
import com.actilazion.aries_transaction.payment.application.PaymentQrService;
import com.actilazion.aries_transaction.transaction.domain.TransferPreview;
import com.actilazion.aries_transaction.transaction.domain.TransferPreviewMode;
import com.actilazion.aries_transaction.transaction.dto.TransferPreviewRequest;
import com.actilazion.aries_transaction.transaction.infrastructure.TransferPreviewRepository;
import com.actilazion.aries_transaction.payment.domain.PaymentQrCode;
import com.actilazion.aries_transaction.payment.domain.QrType;
import com.actilazion.aries_transaction.transaction.domain.exception.RecipientUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferPreviewServiceTest {
    @Mock AccountRepository accountRepository;
    @Mock UserRepository userRepository;
    @Mock TransferPreviewRepository previewRepository;
    @Mock AuditLogService auditLogService;
    @Mock com.actilazion.aries_transaction.smartotp.application.SmartOtpTransferGuard smartOtp;
    @Mock PaymentQrService paymentQrService;
    @Spy TransferPreviewProperties properties = new TransferPreviewProperties();
    TransferPreviewServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TransferPreviewServiceImpl(accountRepository, userRepository, previewRepository,
                properties, auditLogService, new TransferPreviewResolver(accountRepository, paymentQrService), smartOtp);
    }

    @Test
    void externalPreview_resolvesByPublicNumberAndMasksRecipient() {
        User owner = user("owner@test.com", "Alice Owner");
        User recipient = user("recipient@test.com", "Bob Recipient");
        Account source = account(owner, "100000000001", "1000000.00");
        Account destination = account(recipient, "100000000002", "0.00");
        when(userRepository.findByEmailWithLock(owner.getEmail())).thenReturn(Optional.of(owner));
        when(smartOtp.enrollmentState(owner.getId())).thenReturn("UNAVAILABLE");
        when(accountRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(accountRepository.findByAccountNumber(destination.getAccountNumber())).thenReturn(Optional.of(destination));
        when(previewRepository.save(any())).thenAnswer(invocation -> {
            TransferPreview p = invocation.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        var response = service.create(new TransferPreviewRequest(
                TransferPreviewMode.EXTERNAL, source.getId(), null, destination.getAccountNumber(),
                "150000.00", "VND", "Payment"), owner.getEmail());

        assertThat(response.recipient().accountNumberMasked()).isEqualTo("********0002");
        assertThat(response.recipient().accountNumberMasked()).doesNotContain(destination.getAccountNumber());
        assertThat(response.amount()).isEqualTo("150000.00");
        assertThat(response.debitTotal()).isEqualTo("150000.00");
    }

    @Test
    void paymentRequestQr_usesBoundAmountAndDerivedRecipientMode() {
        User owner = user("owner@test.com", "Alice Owner");
        User recipient = user("recipient@test.com", "Bob Recipient");
        Account source = account(owner, "100000000001", "5000.00");
        Account destination = account(recipient, "100000000002", "0.00");
        PaymentQrCode qr = PaymentQrCode.create(recipient.getId(), destination.getId(), QrType.PAYMENT_REQUEST,
                new BigDecimal("1500.00"), "Fixed payment", "qr-key", OffsetDateTime.now());
        when(userRepository.findByEmailWithLock(owner.getEmail())).thenReturn(Optional.of(owner));
        when(accountRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(accountRepository.findById(destination.getId())).thenReturn(Optional.of(destination));
        when(paymentQrService.available(qr.getId())).thenReturn(qr);
        when(smartOtp.enrollmentState(owner.getId())).thenReturn("UNAVAILABLE");
        when(previewRepository.save(any())).thenAnswer(invocation -> {
            TransferPreview preview = invocation.getArgument(0);
            preview.setId(UUID.randomUUID());
            assertThat(preview.getMode()).isEqualTo(TransferPreviewMode.EXTERNAL);
            assertThat(preview.getDescription()).isEqualTo("Fixed payment");
            assertThat(preview.getExpiresAt()).isBeforeOrEqualTo(qr.getExpiresAt());
            return preview;
        });

        var response = service.create(new TransferPreviewRequest(null, source.getId(), null, null,
                null, null, null, qr.getId()), owner.getEmail());

        assertThat(response.amount()).isEqualTo("1500.00");
        assertThat(response.recipient().accountNumberMasked()).isEqualTo("********0002");
    }

    @Test
    void ownAccountPreview_rejectsForeignDestination() {
        User owner = user("owner@test.com", "Alice Owner");
        User foreign = user("foreign@test.com", "Foreign Owner");
        Account source = account(owner, "100000000001", "5000.00");
        Account destination = account(foreign, "100000000002", "0.00");
        when(userRepository.findByEmailWithLock(owner.getEmail())).thenReturn(Optional.of(owner));
        when(accountRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(accountRepository.findById(destination.getId())).thenReturn(Optional.of(destination));

        assertThatThrownBy(() -> service.create(new TransferPreviewRequest(TransferPreviewMode.OWN_ACCOUNTS,
                source.getId(), destination.getId(), null, "1500.00", "VND", null), owner.getEmail()))
                .isInstanceOf(RecipientUnavailableException.class);
    }

    private User user(String email, String name) {
        return User.builder().id(UUID.randomUUID()).email(email).fullName(name)
                .passwordHash("hash").role(Role.USER).isActive(true).build();
    }

    private Account account(User owner, String number, String balance) {
        return Account.builder().id(UUID.randomUUID()).user(owner).accountNumber(number)
                .accountType(AccountType.PERSONAL).balance(new BigDecimal(balance))
                .currency("VND").status(AccountStatus.ACTIVE).build();
    }
}
