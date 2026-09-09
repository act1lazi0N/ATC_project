package com.actilazion.aries_transaction.outbox;

import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.outbox.infrastructure.worker.FanoutOutboxEventPublisher;
import com.actilazion.aries_transaction.outbox.infrastructure.worker.OutboxEventSink;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FanoutOutboxEventPublisherTest {
    @Test
    void publish_requiresAllApplicableSinksToAccept() {
        OutboxEvent event = OutboxEvent.builder().eventType("TransferCompleted").build();
        OutboxEventSink webhook = sink("webhook", event, true);
        OutboxEventSink notification = sink("notification", event, true);

        assertThat(new FanoutOutboxEventPublisher(List.of(webhook, notification)).publish(event)).isTrue();

        verify(webhook).accept(event);
        verify(notification).accept(event);
    }

    @Test
    void publish_failureStopsAcceptanceAndLetsOutboxRetry() {
        OutboxEvent event = OutboxEvent.builder().eventType("TransferCompleted").build();
        OutboxEventSink first = sink("first", event, true);
        OutboxEventSink second = sink("second", event, true);
        OutboxEventSink third = sink("third", event, true);
        org.mockito.Mockito.doThrow(new IllegalStateException("sink unavailable")).when(second).accept(event);

        FanoutOutboxEventPublisher publisher = new FanoutOutboxEventPublisher(List.of(first, second, third));

        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalStateException.class);
        verify(first).accept(event);
        verify(third, never()).accept(event);
    }

    @Test
    void publish_withoutApplicableSinkRemainsUnaccepted() {
        OutboxEvent event = OutboxEvent.builder().eventType("Unknown").build();
        OutboxEventSink sink = sink("notification", event, false);

        assertThat(new FanoutOutboxEventPublisher(List.of(sink)).publish(event)).isFalse();
        verify(sink, never()).accept(event);
    }

    private OutboxEventSink sink(String name, OutboxEvent event, boolean supports) {
        OutboxEventSink sink = mock(OutboxEventSink.class);
        when(sink.name()).thenReturn(name);
        when(sink.supports(event)).thenReturn(supports);
        return sink;
    }
}
