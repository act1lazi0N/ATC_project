package com.actilazion.aries_transaction.webhook;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionOperation;
import com.actilazion.aries_transaction.webhook.application.WebhookEventPayloadFactory;
import com.actilazion.aries_transaction.webhook.domain.WebhookEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookEventPayloadFactoryTest {
    private final WebhookEventPayloadFactory factory = new WebhookEventPayloadFactory();

    @Test
    void create_projectsExactMoneyAndMasksTheCounterpartyWithoutLeakingInternalFields() {
        User sender = user(Role.USER, "Sender Person");
        User merchant = user(Role.MERCHANT, "Merchant Person");
        Account senderAccount = account(sender, "111122223333");
        Account merchantAccount = account(merchant, "999988887777");
        Transaction transaction = transaction(sender, senderAccount, merchantAccount, TransactionOperation.TRANSFER);
        OutboxEvent event = event(transaction, "TransferCompleted");
        UUID deliveryId = UUID.randomUUID();

        Map<String, Object> envelope = factory.create(
                event, transaction, merchant.getId(), deliveryId, WebhookEventType.TRANSFER_COMPLETED);

        assertThat(envelope)
                .containsEntry("eventId", event.getId().toString())
                .containsEntry("deliveryId", deliveryId.toString())
                .containsEntry("type", "transaction.transfer.completed.v1")
                .containsEntry("version", 1)
                .containsEntry("occurredAt", "2026-09-05T01:02:03Z");

        Map<String, Object> data = nested(envelope, "data");
        assertThat(data)
                .containsEntry("amount", "100000.00")
                .containsEntry("currency", "VND")
                .containsEntry("status", "COMPLETED")
                .containsEntry("direction", "INCOMING");
        assertThat(nested(data, "fromParty"))
                .containsEntry("accountNumberDisplay", "********3333")
                .containsEntry("exposure", "MASKED_COUNTERPARTY")
                .containsEntry("ownedByMerchant", false);
        assertThat(nested(data, "toParty"))
                .containsEntry("accountNumberDisplay", "999988887777")
                .containsEntry("exposure", "FULL_OWNED")
                .containsEntry("ownedByMerchant", true);

        String publicPayload = envelope.toString();
        assertThat(publicPayload)
                .doesNotContain(sender.getId().toString())
                .doesNotContain(sender.getFullName())
                .doesNotContain(merchant.getFullName())
                .doesNotContain("internal-idempotency-key")
                .doesNotContain("111122223333");
    }

    @Test
    void create_projectsOwnAccountDirectionOnceForTheMerchant() {
        User merchant = user(Role.MERCHANT, "Merchant Person");
        Account first = account(merchant, "111100001111");
        Account second = account(merchant, "222200002222");
        Transaction transaction = transaction(merchant, first, second, TransactionOperation.TRANSFER);
        OutboxEvent event = event(transaction, "TransferCompleted");

        Map<String, Object> envelope = factory.create(
                event, transaction, merchant.getId(), UUID.randomUUID(), WebhookEventType.TRANSFER_COMPLETED);

        assertThat(nested(envelope, "data")).containsEntry("direction", "OWN_ACCOUNTS");
        assertThat(nested(nested(envelope, "data"), "fromParty"))
                .containsEntry("accountNumberDisplay", "111100001111")
                .containsEntry("exposure", "FULL_OWNED");
        assertThat(nested(nested(envelope, "data"), "toParty"))
                .containsEntry("accountNumberDisplay", "222200002222")
                .containsEntry("exposure", "FULL_OWNED");
    }

    @ParameterizedTest
    @EnumSource(WebhookEventType.class)
    void create_mapsEverySupportedOutboxTypeToItsVersionedContract(WebhookEventType eventType) {
        User merchant = user(Role.MERCHANT, "Merchant Person");
        User counterparty = user(Role.USER, "Counterparty Person");
        Transaction transaction = transaction(
                merchant,
                account(merchant, "555500005555"),
                account(counterparty, "666600006666"),
                eventType.operation()
        );
        OutboxEvent event = event(transaction, eventType.outboxEventType());

        Map<String, Object> envelope = factory.create(
                event, transaction, merchant.getId(), UUID.randomUUID(), eventType);

        assertThat(envelope).containsEntry("type", eventType.publicType());
        assertThat(nested(envelope, "data"))
                .containsEntry("operation", eventType.operation().name())
                .containsEntry("direction", "OUTGOING");
    }

    private User user(Role role, String fullName) {
        return User.builder()
                .id(UUID.randomUUID())
                .fullName(fullName)
                .email(UUID.randomUUID() + "@test.local")
                .passwordHash("hashed")
                .role(role)
                .build();
    }

    private Account account(User owner, String number) {
        return Account.builder().id(UUID.randomUUID()).user(owner).accountNumber(number).build();
    }

    private Transaction transaction(
            User initiator,
            Account from,
            Account to,
            TransactionOperation operation
    ) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .fromAccount(from)
                .toAccount(to)
                .initiatedBy(initiator)
                .operation(operation)
                .build();
    }

    private OutboxEvent event(Transaction transaction, String eventType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", "100000.00");
        payload.put("currency", "VND");
        payload.put("status", "COMPLETED");
        payload.put("completedAt", "2026-09-05T01:02:03Z");
        payload.put("originalTransactionId", null);
        payload.put("fromUserFullName", transaction.getFromAccount().getUser().getFullName());
        payload.put("toUserFullName", transaction.getToAccount().getUser().getFullName());
        payload.put("fromAccountNumber", transaction.getFromAccount().getAccountNumber());
        payload.put("toAccountNumber", transaction.getToAccount().getAccountNumber());
        payload.put("userId", transaction.getInitiatedBy().getId().toString());
        payload.put("idempotencyKey", "internal-idempotency-key");
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Transaction")
                .aggregateId(transaction.getId())
                .eventType(eventType)
                .payload(payload)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nested(Map<String, Object> value, String key) {
        return (Map<String, Object>) value.get(key);
    }
}
