package com.actilazion.aries_transaction.transaction;

import com.actilazion.aries_transaction.account.application.AccountPartyMasking;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.transaction.application.TransactionReadProjection;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.dto.AccountNumberExposure;
import com.actilazion.aries_transaction.transaction.dto.TransactionDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionReadProjectionTest {
    @Test
    void ownerSeesFullOwnedPartyAndMaskedCounterparty() {
        User owner = user("Owner");
        User recipient = user("Recipient");
        Account source = account(owner, "123456789013");
        Account destination = account(recipient, "987654321098");
        Transaction transaction = transaction(source, destination);

        var response = TransactionReadProjection.project(transaction, owner, null);

        assertThat(response.fromParty().exposure()).isEqualTo(AccountNumberExposure.FULL_OWNED);
        assertThat(response.fromParty().accountNumberDisplay()).isEqualTo(source.getAccountNumber());
        assertThat(response.fromParty().ownedByRequester()).isTrue();
        assertThat(response.toParty().exposure()).isEqualTo(AccountNumberExposure.MASKED_COUNTERPARTY);
        assertThat(response.toParty().accountNumberDisplay())
                .isEqualTo(AccountPartyMasking.maskedNumber(destination));
        assertThat(response.toParty().accountNumberDisplay()).doesNotContain(destination.getAccountNumber());
        assertThat(response.direction()).isEqualTo(TransactionDirection.OUTGOING);
    }

    @Test
    void privilegedReaderWithoutOwnedContextGetsMaskedPartiesAndUnknownDirection() {
        User operator = user("Operator");
        Account source = account(user("Source"), "123456789013");
        Account destination = account(user("Destination"), "987654321098");

        var response = TransactionReadProjection.project(transaction(source, destination), operator, null);

        assertThat(response.fromParty().exposure()).isEqualTo(AccountNumberExposure.MASKED_COUNTERPARTY);
        assertThat(response.toParty().exposure()).isEqualTo(AccountNumberExposure.MASKED_COUNTERPARTY);
        assertThat(response.direction()).isEqualTo(TransactionDirection.UNKNOWN);
    }

    @Test
    void selectedOwnedDestinationIsIncoming() {
        User owner = user("Owner");
        Account source = account(user("Source"), "123456789013");
        Account destination = account(owner, "987654321098");

        var response = TransactionReadProjection.project(
                transaction(source, destination), owner, destination.getId());

        assertThat(response.direction()).isEqualTo(TransactionDirection.INCOMING);
        assertThat(response.toParty().exposure()).isEqualTo(AccountNumberExposure.FULL_OWNED);
    }

    @Test
    void selectedOwnedSourceAndDestinationAreOwnAccounts() {
        User owner = user("Owner");
        Account source = account(owner, "123456789013");
        Account destination = account(owner, "987654321098");

        var response = TransactionReadProjection.project(
                transaction(source, destination), owner, source.getId());

        assertThat(response.direction()).isEqualTo(TransactionDirection.OWN_ACCOUNTS);
        assertThat(response.fromParty().exposure()).isEqualTo(AccountNumberExposure.FULL_OWNED);
        assertThat(response.toParty().exposure()).isEqualTo(AccountNumberExposure.FULL_OWNED);
    }

    @Test
    void missingPartyIsExplicitlyUnavailable() {
        User owner = user("Owner");
        Transaction transaction = Transaction.builder()
                .id(UUID.randomUUID())
                .fromAccount(null)
                .toAccount(account(owner, "987654321098"))
                .initiatedBy(owner)
                .amount(new BigDecimal("1000.00"))
                .currency("VND")
                .idempotencyKey(UUID.randomUUID().toString())
                .build();

        var response = TransactionReadProjection.project(transaction, owner, null);

        assertThat(response.fromParty().exposure()).isEqualTo(AccountNumberExposure.UNAVAILABLE);
        assertThat(response.direction()).isEqualTo(TransactionDirection.INCOMING);
    }

    private Transaction transaction(Account source, Account destination) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .fromAccount(source)
                .toAccount(destination)
                .initiatedBy(source.getUser())
                .amount(new BigDecimal("1000.00"))
                .currency("VND")
                .idempotencyKey(UUID.randomUUID().toString())
                .build();
    }

    private Account account(User owner, String number) {
        return Account.builder()
                .id(UUID.randomUUID())
                .user(owner)
                .accountNumber(number)
                .accountType(AccountType.PERSONAL)
                .balance(BigDecimal.ZERO)
                .currency("VND")
                .build();
    }

    private User user(String name) {
        return User.builder()
                .id(UUID.randomUUID())
                .fullName(name)
                .email(name.toLowerCase() + "@test.local")
                .passwordHash("hashed")
                .role(Role.USER)
                .build();
    }
}
