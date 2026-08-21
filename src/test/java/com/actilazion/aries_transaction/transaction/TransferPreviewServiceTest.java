package com.actilazion.aries_transaction.transaction;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.transaction.application.TransferPreviewServiceImpl;
import com.actilazion.aries_transaction.transaction.domain.TransferPreview;
import com.actilazion.aries_transaction.transaction.domain.TransferPreviewMode;
import com.actilazion.aries_transaction.transaction.dto.TransferPreviewRequest;
import com.actilazion.aries_transaction.transaction.infrastructure.TransferPreviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferPreviewServiceTest {
    @Mock AccountRepository accountRepository;
    @Mock UserRepository userRepository;
    @Mock TransferPreviewRepository previewRepository;
    @InjectMocks TransferPreviewServiceImpl service;

    @Test
    void externalPreview_resolvesByPublicNumberAndMasksRecipient() {
        User owner = user("owner@test.com", "Alice Owner");
        User recipient = user("recipient@test.com", "Bob Recipient");
        Account source = account(owner, "100000000001", "1000000.00");
        Account destination = account(recipient, "100000000002", "0.00");
        when(userRepository.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
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

    private User user(String email, String name) {
        return User.builder().id(UUID.randomUUID()).email(email).fullName(name)
                .passwordHash("hash").role(Role.USER).build();
    }

    private Account account(User owner, String number, String balance) {
        return Account.builder().id(UUID.randomUUID()).user(owner).accountNumber(number)
                .accountType(AccountType.PERSONAL).balance(new BigDecimal(balance))
                .currency("VND").status(AccountStatus.ACTIVE).build();
    }
}
