package com.actilazion.aries_transaction.notification;

import com.actilazion.aries_transaction.notification.application.EmailDeliveryService;
import com.actilazion.aries_transaction.notification.application.EmailDeliveryWorkItem;
import com.actilazion.aries_transaction.notification.application.NotificationProperties;
import com.actilazion.aries_transaction.notification.application.NotificationMetrics;
import com.actilazion.aries_transaction.notification.infrastructure.email.EmailDeliveryWorker;
import com.actilazion.aries_transaction.notification.infrastructure.email.EmailGateway;
import com.actilazion.aries_transaction.notification.infrastructure.email.EmailGatewayException;
import com.actilazion.aries_transaction.notification.infrastructure.email.EmailMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailDeliveryWorkerTest {
    @Test
    void acceptedEmailIsMarkedDelivered() {
        EmailDeliveryService service = mock(EmailDeliveryService.class);
        EmailGateway gateway = mock(EmailGateway.class);
        NotificationProperties properties = properties();
        NotificationMetrics metrics = mock(NotificationMetrics.class);
        EmailDeliveryWorkItem work = work();
        when(service.findDueIds(25)).thenReturn(List.of(work.deliveryId()));
        when(service.claim(work.deliveryId())).thenReturn(Optional.of(work));

        new EmailDeliveryWorker(service, gateway, properties, metrics).deliverPending();

        verify(gateway).send(work.message());
        verify(service).markDelivered(org.mockito.ArgumentMatchers.eq(work), anyLong());
        verify(metrics).emailOutcome("delivered");
    }

    @Test
    void retryableGatewayFailureIsPersistedWithoutThrowingFromWorker() {
        EmailDeliveryService service = mock(EmailDeliveryService.class);
        EmailGateway gateway = mock(EmailGateway.class);
        NotificationProperties properties = properties();
        NotificationMetrics metrics = mock(NotificationMetrics.class);
        EmailDeliveryWorkItem work = work();
        when(service.findDueIds(25)).thenReturn(List.of(work.deliveryId()));
        when(service.claim(work.deliveryId())).thenReturn(Optional.of(work));
        doThrow(new EmailGatewayException(true, "SMTP_TEMPORARY_FAILURE", new RuntimeException()))
                .when(gateway).send(work.message());

        new EmailDeliveryWorker(service, gateway, properties, metrics).deliverPending();

        verify(service).markFailed(
                org.mockito.ArgumentMatchers.eq(work),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq("SMTP_TEMPORARY_FAILURE"),
                anyLong());
        verify(metrics).emailOutcome("retryable_failure");
    }

    @Test
    void deliveredStatePersistenceFailureDoesNotRewriteAcceptedEmailAsSendFailure() {
        EmailDeliveryService service = mock(EmailDeliveryService.class);
        EmailGateway gateway = mock(EmailGateway.class);
        NotificationProperties properties = properties();
        NotificationMetrics metrics = mock(NotificationMetrics.class);
        EmailDeliveryWorkItem work = work();
        when(service.findDueIds(25)).thenReturn(List.of(work.deliveryId()));
        when(service.claim(work.deliveryId())).thenReturn(Optional.of(work));
        doThrow(new IllegalStateException("database unavailable"))
                .when(service).markDelivered(org.mockito.ArgumentMatchers.eq(work), anyLong());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new EmailDeliveryWorker(service, gateway, properties, metrics).deliverPending())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        verify(gateway).send(work.message());
        verify(service, never()).markFailed(
                org.mockito.ArgumentMatchers.eq(work),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyString(),
                anyLong());
        verify(metrics, never()).emailOutcome("delivered");
    }

    @Test
    void nextMessageIsClaimedOnlyAfterPreviousSendCompletes() {
        EmailDeliveryService service = mock(EmailDeliveryService.class);
        EmailGateway gateway = mock(EmailGateway.class);
        EmailDeliveryWorkItem first = work();
        EmailDeliveryWorkItem second = work();
        UUID cancelled = UUID.randomUUID();
        when(service.findDueIds(25)).thenReturn(List.of(first.deliveryId(), cancelled, second.deliveryId()));
        when(service.claim(first.deliveryId())).thenReturn(Optional.of(first));
        when(service.claim(cancelled)).thenReturn(Optional.empty());
        when(service.claim(second.deliveryId())).thenReturn(Optional.of(second));

        new EmailDeliveryWorker(service, gateway, properties(), mock(NotificationMetrics.class)).deliverPending();

        var order = org.mockito.Mockito.inOrder(service, gateway);
        order.verify(service).claim(first.deliveryId());
        order.verify(gateway).send(first.message());
        order.verify(service).markDelivered(org.mockito.ArgumentMatchers.eq(first), anyLong());
        order.verify(service).claim(cancelled);
        order.verify(service).claim(second.deliveryId());
        order.verify(gateway).send(second.message());
    }

    private NotificationProperties properties() {
        NotificationProperties properties = new NotificationProperties();
        properties.getEmail().setBatchSize(25);
        return properties;
    }

    private EmailDeliveryWorkItem work() {
        UUID id = UUID.randomUUID();
        return new EmailDeliveryWorkItem(
                id, UUID.randomUUID(), 1,
                new EmailMessage("masked@test.local", "no-reply@test.local", "Subject", "Text", "<p>Text</p>", id.toString())
        );
    }
}
