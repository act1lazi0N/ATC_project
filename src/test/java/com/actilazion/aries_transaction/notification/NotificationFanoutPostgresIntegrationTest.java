package com.actilazion.aries_transaction.notification;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.notification.application.NotificationFanoutResult;
import com.actilazion.aries_transaction.notification.application.NotificationFanoutService;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryStatus;
import com.actilazion.aries_transaction.notification.domain.Notification;
import com.actilazion.aries_transaction.notification.domain.NotificationSourceKind;
import com.actilazion.aries_transaction.notification.infrastructure.EmailDeliveryRepository;
import com.actilazion.aries_transaction.notification.infrastructure.NotificationRepository;
import com.actilazion.aries_transaction.outbox.application.OutboxEventService;
import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.outbox.domain.OutboxEventType;
import com.actilazion.aries_transaction.outbox.infrastructure.OutboxEventRepository;
import com.actilazion.aries_transaction.support.PostgresIntegrationTestSupport;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionOperation;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class NotificationFanoutPostgresIntegrationTest extends PostgresIntegrationTestSupport {
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired OutboxEventService outboxEventService;
    @Autowired NotificationFanoutService fanoutService;
    @Autowired NotificationRepository notificationRepository;
    @Autowired EmailDeliveryRepository emailDeliveryRepository;

    @Test
    void fanOut_createsRecipientNotificationsAndEmailJobsWithoutSensitiveData() {
        User sender = user(true);
        User receiver = user(true);
        Account from = account(sender, "810000000001");
        Account to = account(receiver, "820000000001");
        OutboxEvent event = event(sender, from, to);

        assertThat(fanoutService.fanOut(event.getId())).isEqualTo(new NotificationFanoutResult(2, 2));
        assertThat(fanoutService.fanOut(event.getId())).isEqualTo(new NotificationFanoutResult(2, 0));

        List<Notification> notifications = notificationRepository
                .findAllBySourceKindAndSourceIdOrderByRecipient_Id(NotificationSourceKind.OUTBOX_EVENT, event.getId());
        assertThat(notifications).hasSize(2).allSatisfy(notification -> {
            assertThat(notification.getPayload()).containsEntry("amount", "100000.00");
            assertThat(notification.getPayload().toString())
                    .doesNotContain(sender.getId().toString())
                    .doesNotContain(receiver.getId().toString())
                    .doesNotContain(from.getId().toString())
                    .doesNotContain(to.getId().toString())
                    .doesNotContain(sender.getFullName())
                    .doesNotContain(from.getAccountNumber());
        });
        assertThat(emailDeliveryRepository.findAll()).hasSize(2)
                .allSatisfy(delivery -> assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.PENDING));
    }

    @Test
    void fanOut_sameOwnerCreatesOneOwnAccountsNotification() {
        long emailCountBefore = emailDeliveryRepository.count();
        User owner = user(false);
        Account from = account(owner, "830000000001");
        Account to = account(owner, "830000000002");
        OutboxEvent event = event(owner, from, to);

        assertThat(fanoutService.fanOut(event.getId())).isEqualTo(new NotificationFanoutResult(1, 1));
        Notification notification = notificationRepository
                .findAllBySourceKindAndSourceIdOrderByRecipient_Id(NotificationSourceKind.OUTBOX_EVENT, event.getId())
                .getFirst();
        assertThat(notification.getPayload()).containsEntry("direction", "OWN_ACCOUNTS");
        assertThat(emailDeliveryRepository.count()).isEqualTo(emailCountBefore);
    }

    @Test
    void fanOut_concurrentReplayCreatesNoDuplicates() throws Exception {
        User sender = user(false);
        User receiver = user(false);
        OutboxEvent event = event(sender, account(sender, "840000000001"), account(receiver, "850000000001"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<NotificationFanoutResult> first = executor.submit(() -> afterBarrier(event.getId(), ready, start));
            Future<NotificationFanoutResult> second = executor.submit(() -> afterBarrier(event.getId(), ready, start));
            ready.await();
            start.countDown();
            assertThat(List.of(first.get(), second.get()))
                    .extracting(NotificationFanoutResult::createdNotificationCount)
                    .containsExactlyInAnyOrder(0, 2);
        }
        assertThat(notificationRepository
                .findAllBySourceKindAndSourceIdOrderByRecipient_Id(NotificationSourceKind.OUTBOX_EVENT, event.getId()))
                .hasSize(2);
    }

    private NotificationFanoutResult afterBarrier(UUID id, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        return fanoutService.fanOut(id);
    }

    private User user(boolean verified) {
        String id = UUID.randomUUID().toString();
        return userRepository.saveAndFlush(User.builder()
                .fullName("Notification " + id)
                .email("notification-" + id + "@test.local")
                .passwordHash("hashed")
                .role(Role.USER)
                .isActive(true)
                .emailVerifiedAt(verified ? OffsetDateTime.now() : null)
                .build());
    }

    private Account account(User user, String number) {
        return accountRepository.saveAndFlush(Account.builder()
                .user(user)
                .accountNumber(number)
                .accountType(AccountType.PERSONAL)
                .balance(new BigDecimal("500000.00"))
                .currency("VND")
                .status(AccountStatus.ACTIVE)
                .build());
    }

    private OutboxEvent event(User initiator, Account from, Account to) {
        Transaction transaction = Transaction.builder()
                .fromAccount(from).toAccount(to).initiatedBy(initiator)
                .amount(new BigDecimal("100000.00")).currency("VND")
                .status(TransactionStatus.PENDING).operation(TransactionOperation.TRANSFER)
                .idempotencyKey(UUID.randomUUID().toString()).build();
        transaction.markCompleted(OffsetDateTime.now());
        transaction = transactionRepository.saveAndFlush(transaction);
        outboxEventService.recordTransferCompleted(transaction);
        return outboxEventRepository.findByAggregateTypeAndAggregateIdAndEventType(
                OutboxEventType.TRANSFER_COMPLETED.aggregateType(),
                transaction.getId(),
                OutboxEventType.TRANSFER_COMPLETED.eventType()).orElseThrow();
    }
}
