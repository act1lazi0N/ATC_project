package com.actilazion.aries_transaction.webhook;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.outbox.application.OutboxEventService;
import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.outbox.domain.OutboxEventType;
import com.actilazion.aries_transaction.outbox.infrastructure.OutboxEventRepository;
import com.actilazion.aries_transaction.support.PostgresIntegrationTestSupport;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionOperation;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import com.actilazion.aries_transaction.webhook.application.WebhookFanoutResult;
import com.actilazion.aries_transaction.webhook.application.WebhookFanoutService;
import com.actilazion.aries_transaction.webhook.domain.WebhookDelivery;
import com.actilazion.aries_transaction.webhook.domain.WebhookDeliveryStatus;
import com.actilazion.aries_transaction.webhook.domain.WebhookEndpoint;
import com.actilazion.aries_transaction.webhook.domain.WebhookEndpointState;
import com.actilazion.aries_transaction.webhook.domain.WebhookEndpointSubscription;
import com.actilazion.aries_transaction.webhook.domain.WebhookEventType;
import com.actilazion.aries_transaction.webhook.infrastructure.WebhookDeliveryRepository;
import com.actilazion.aries_transaction.webhook.infrastructure.WebhookEndpointRepository;
import com.actilazion.aries_transaction.webhook.infrastructure.WebhookEndpointSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class WebhookFanoutPostgresIntegrationTest extends PostgresIntegrationTestSupport {
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired OutboxEventService outboxEventService;
    @Autowired WebhookEndpointRepository endpointRepository;
    @Autowired WebhookEndpointSubscriptionRepository subscriptionRepository;
    @Autowired WebhookDeliveryRepository deliveryRepository;
    @Autowired WebhookFanoutService fanoutService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void fanOut_createsIndependentDeliveriesForEachEndpointAndReplayIsIdempotent() {
        User sender = saveUser(Role.USER, true);
        User merchant = saveUser(Role.MERCHANT, true);
        Account senderAccount = saveAccount(sender, "110000000001");
        Account merchantAccount = saveAccount(merchant, "220000000001");
        subscribe(merchant, "primary", "https://primary.example/webhooks", WebhookEventType.TRANSFER_COMPLETED);
        subscribe(merchant, "backup", "https://backup.example/webhooks", WebhookEventType.TRANSFER_COMPLETED);
        OutboxEvent event = completedEvent(sender, senderAccount, merchantAccount, TransactionOperation.TRANSFER);

        WebhookFanoutResult first = fanoutService.fanOut(event.getId());
        WebhookFanoutResult replay = fanoutService.fanOut(event.getId());

        assertThat(first).isEqualTo(new WebhookFanoutResult(2, 2));
        assertThat(replay).isEqualTo(new WebhookFanoutResult(2, 0));
        List<WebhookDelivery> deliveries = deliveryRepository.findAllByOutboxEventIdOrderByEndpoint_Id(event.getId());
        assertThat(deliveries).hasSize(2).allSatisfy(delivery -> {
            assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
            assertThat(delivery.getEventType()).isEqualTo("transaction.transfer.completed.v1");
            assertThat(delivery.getPayload())
                    .containsEntry("eventId", event.getId().toString())
                    .containsEntry("deliveryId", delivery.getId().toString());
            Map<String, Object> data = nested(delivery.getPayload(), "data");
            assertThat(data)
                    .containsEntry("amount", "100000.00")
                    .containsEntry("direction", "INCOMING");
            assertThat(delivery.getPayload().toString())
                    .doesNotContain(sender.getFullName())
                    .doesNotContain(senderAccount.getAccountNumber())
                    .doesNotContain(sender.getId().toString());
        });
    }

    @Test
    void fanOut_sameMerchantOwnsBothAccounts_createsOneOwnAccountsDelivery() {
        User merchant = saveUser(Role.MERCHANT, true);
        Account first = saveAccount(merchant, "330000000001");
        Account second = saveAccount(merchant, "330000000002");
        subscribe(merchant, "own", "https://own.example/webhooks", WebhookEventType.TRANSFER_COMPLETED);
        OutboxEvent event = completedEvent(merchant, first, second, TransactionOperation.TRANSFER);

        fanoutService.fanOut(event.getId());

        WebhookDelivery delivery = deliveryRepository
                .findAllByOutboxEventIdOrderByEndpoint_Id(event.getId()).getFirst();
        assertThat(nested(delivery.getPayload(), "data")).containsEntry("direction", "OWN_ACCOUNTS");
        assertThat(deliveryRepository.findAllByOutboxEventIdOrderByEndpoint_Id(event.getId())).hasSize(1);
    }

    @Test
    void fanOut_ignoresSubscriptionsCreatedAfterTheEventAndInactiveMerchants() {
        User sender = saveUser(Role.USER, true);
        User merchant = saveUser(Role.MERCHANT, true);
        Account senderAccount = saveAccount(sender, "440000000001");
        Account merchantAccount = saveAccount(merchant, "550000000001");
        OutboxEvent historicalEvent = completedEvent(
                sender, senderAccount, merchantAccount, TransactionOperation.TRANSFER);
        jdbcTemplate.update(
                "UPDATE outbox_events SET created_at = created_at - INTERVAL '1 hour' WHERE id = ?",
                historicalEvent.getId()
        );
        subscribe(merchant, "late", "https://late.example/webhooks", WebhookEventType.TRANSFER_COMPLETED);

        assertThat(fanoutService.fanOut(historicalEvent.getId()))
                .isEqualTo(new WebhookFanoutResult(0, 0));

        OutboxEvent currentEvent = completedEvent(
                sender, senderAccount, merchantAccount, TransactionOperation.TRANSFER);
        merchant.setIsActive(false);
        userRepository.saveAndFlush(merchant);

        assertThat(fanoutService.fanOut(currentEvent.getId()))
                .isEqualTo(new WebhookFanoutResult(0, 0));
    }

    @Test
    void fanOut_concurrentReplaySerializesOnOutboxAndCreatesNoDuplicates() throws Exception {
        User sender = saveUser(Role.USER, true);
        User merchant = saveUser(Role.MERCHANT, true);
        Account senderAccount = saveAccount(sender, "660000000001");
        Account merchantAccount = saveAccount(merchant, "770000000001");
        subscribe(merchant, "concurrent", "https://concurrent.example/webhooks", WebhookEventType.TRANSFER_COMPLETED);
        OutboxEvent event = completedEvent(sender, senderAccount, merchantAccount, TransactionOperation.TRANSFER);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<WebhookFanoutResult> first = executor.submit(() -> fanOutAfterBarrier(event.getId(), ready, start));
            Future<WebhookFanoutResult> second = executor.submit(() -> fanOutAfterBarrier(event.getId(), ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .extracting(WebhookFanoutResult::createdDeliveryCount)
                    .containsExactlyInAnyOrder(0, 1);
        }
        assertThat(deliveryRepository.findAllByOutboxEventIdOrderByEndpoint_Id(event.getId())).hasSize(1);
    }

    private WebhookFanoutResult fanOutAfterBarrier(
            UUID eventId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return fanoutService.fanOut(eventId);
    }

    private User saveUser(Role role, boolean active) {
        String suffix = UUID.randomUUID().toString();
        return userRepository.saveAndFlush(User.builder()
                .fullName(role + " " + suffix)
                .email(role.name().toLowerCase() + "-" + suffix + "@test.local")
                .passwordHash("hashed")
                .role(role)
                .isActive(active)
                .build());
    }

    private Account saveAccount(User owner, String accountNumber) {
        return accountRepository.saveAndFlush(Account.builder()
                .user(owner)
                .accountNumber(accountNumber)
                .accountType(AccountType.PERSONAL)
                .balance(new BigDecimal("500000.00"))
                .currency("VND")
                .status(AccountStatus.ACTIVE)
                .build());
    }

    private WebhookEndpoint subscribe(
            User owner,
            String name,
            String url,
            WebhookEventType eventType
    ) {
        WebhookEndpoint endpoint = endpointRepository.saveAndFlush(WebhookEndpoint.builder()
                .owner(owner)
                .name(name)
                .canonicalUrl(url)
                .signingSecretCiphertext("encrypted-test-secret")
                .signingSecretNonce("test-nonce")
                .secretKeyVersion("v1")
                .secretHint("...test")
                .state(WebhookEndpointState.ENABLED)
                .build());
        subscriptionRepository.saveAndFlush(WebhookEndpointSubscription.builder()
                .endpoint(endpoint)
                .eventType(eventType)
                .build());
        return endpoint;
    }

    private OutboxEvent completedEvent(
            User initiator,
            Account from,
            Account to,
            TransactionOperation operation
    ) {
        Transaction transaction = Transaction.builder()
                .fromAccount(from)
                .toAccount(to)
                .initiatedBy(initiator)
                .amount(new BigDecimal("100000.00"))
                .currency("VND")
                .status(TransactionStatus.PENDING)
                .operation(operation)
                .idempotencyKey(UUID.randomUUID().toString())
                .build();
        transaction.markCompleted(OffsetDateTime.now());
        transaction = transactionRepository.saveAndFlush(transaction);
        OutboxEventType outboxType = switch (operation) {
            case TRANSFER -> OutboxEventType.TRANSFER_COMPLETED;
            case REVERSAL -> OutboxEventType.REVERSAL_COMPLETED;
            case REFUND -> OutboxEventType.REFUND_COMPLETED;
        };
        switch (outboxType) {
            case TRANSFER_COMPLETED -> outboxEventService.recordTransferCompleted(transaction);
            case REVERSAL_COMPLETED -> outboxEventService.recordReversalCompleted(transaction);
            case REFUND_COMPLETED -> outboxEventService.recordRefundCompleted(transaction);
        }
        return outboxEventRepository.findByAggregateTypeAndAggregateIdAndEventType(
                outboxType.aggregateType(), transaction.getId(), outboxType.eventType()).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nested(Map<String, Object> value, String key) {
        return (Map<String, Object>) value.get(key);
    }
}
