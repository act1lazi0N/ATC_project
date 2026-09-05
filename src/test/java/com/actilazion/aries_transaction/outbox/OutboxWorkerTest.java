package com.actilazion.aries_transaction.outbox;

import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.outbox.domain.OutboxEventStatus;
import com.actilazion.aries_transaction.outbox.application.OutboxEventService;
import com.actilazion.aries_transaction.outbox.infrastructure.worker.NoopOutboxEventPublisher;
import com.actilazion.aries_transaction.outbox.infrastructure.worker.OutboxEventPublisher;
import com.actilazion.aries_transaction.outbox.infrastructure.worker.OutboxWorker;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxWorkerTest {

    @Test
    void publishPendingEvents_noopPublisherLeavesEventPending() {
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        OutboxEventPublisher publisher = new NoopOutboxEventPublisher();
        OutboxWorker worker = new OutboxWorker(outboxEventService, publisher);
        ReflectionTestUtils.setField(worker, "batchSize", 25);

        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Transaction")
                .aggregateId(UUID.randomUUID())
                .eventType("TransferCompleted")
                .status(OutboxEventStatus.PENDING)
                .claimToken(UUID.randomUUID())
                .build();

        when(outboxEventService.claimPublishableEvents(25)).thenReturn(List.of(event));

        worker.publishPendingEvents();

        verify(outboxEventService, never()).markPublished(any(), any());
        verify(outboxEventService).markFailed(
                event.getId(),
                event.getClaimToken(),
                "Downstream sink did not confirm durable acceptance"
        );
    }

    @Test
    void publishPendingEvents_durablyAcceptedEventIsMarkedPublished() {
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        OutboxEventPublisher publisher = mock(OutboxEventPublisher.class);
        OutboxWorker worker = new OutboxWorker(outboxEventService, publisher);
        ReflectionTestUtils.setField(worker, "batchSize", 25);
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .claimToken(UUID.randomUUID())
                .build();
        when(outboxEventService.claimPublishableEvents(25)).thenReturn(List.of(event));
        when(publisher.publish(event)).thenReturn(true);

        worker.publishPendingEvents();

        verify(outboxEventService).markPublished(event.getId(), event.getClaimToken());
        verify(outboxEventService, never()).markFailed(any(), any(), any());
    }
}
