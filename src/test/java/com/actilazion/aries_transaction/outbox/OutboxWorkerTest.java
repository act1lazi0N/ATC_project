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
                .build();

        when(outboxEventService.findPendingEvents(25)).thenReturn(List.of(event));

        worker.publishPendingEvents();

        verify(outboxEventService, never()).markPublished(any());
        verify(outboxEventService, never()).markFailed(any());
    }
}
