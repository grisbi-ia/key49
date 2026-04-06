package auracore.key49.queue.outbox;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.queue.producer.DocumentEventProducer;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;

class OutboxEventRouterTest {

    private OutboxEventRouter router;
    private DocumentEventProducer producer;

    @BeforeEach
    void setUp() {
        producer = mock(DocumentEventProducer.class);
        router = new OutboxEventRouter();
        router.producer = producer;
    }

    @Test
    void shouldRouteSignEventToSignChannel() {
        var event = DocumentEvent.of(UUID.randomUUID(), "tenant_test", "doc.sign");
        when(producer.sendToSign(any())).thenReturn(Uni.createFrom().voidItem());

        router.route(event)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        verify(producer).sendToSign(event);
    }

    @Test
    void shouldRouteSendEventToSendChannel() {
        var event = DocumentEvent.of(UUID.randomUUID(), "tenant_test", "doc.send");
        when(producer.sendToSend(any())).thenReturn(Uni.createFrom().voidItem());

        router.route(event)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        verify(producer).sendToSend(event);
    }

    @Test
    void shouldRouteAuthorizeEventToAuthorizeChannel() {
        var event = DocumentEvent.of(UUID.randomUUID(), "tenant_test", "doc.authorize");
        when(producer.sendToAuthorize(any())).thenReturn(Uni.createFrom().voidItem());

        router.route(event)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        verify(producer).sendToAuthorize(event);
    }

    @Test
    void shouldRouteNotifyEventToNotifyChannel() {
        var event = DocumentEvent.of(UUID.randomUUID(), "tenant_test", "doc.notify");
        when(producer.sendToNotify(any())).thenReturn(Uni.createFrom().voidItem());

        router.route(event)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        verify(producer).sendToNotify(event);
    }

    @Test
    void shouldFailForUnknownEventType() {
        var event = DocumentEvent.of(UUID.randomUUID(), "tenant_test", "doc.unknown");

        router.route(event)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertFailedWith(IllegalArgumentException.class, "Unknown event type: doc.unknown");

        verifyNoInteractions(producer);
    }

    @Test
    void shouldPropagateProducerFailure() {
        var event = DocumentEvent.of(UUID.randomUUID(), "tenant_test", "doc.sign");
        when(producer.sendToSign(any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("RabbitMQ down")));

        router.route(event)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertFailedWith(RuntimeException.class, "RabbitMQ down");
    }

    @Test
    void shouldRouteAllFourEventTypes() {
        when(producer.sendToSign(any())).thenReturn(Uni.createFrom().voidItem());
        when(producer.sendToSend(any())).thenReturn(Uni.createFrom().voidItem());
        when(producer.sendToAuthorize(any())).thenReturn(Uni.createFrom().voidItem());
        when(producer.sendToNotify(any())).thenReturn(Uni.createFrom().voidItem());

        var types = new String[]{"doc.sign", "doc.send", "doc.authorize", "doc.notify"};
        for (var type : types) {
            var event = DocumentEvent.of(UUID.randomUUID(), "tenant_test", type);
            var result = router.route(event)
                    .subscribe().withSubscriber(UniAssertSubscriber.create())
                    .getItem();
            // Should complete without error
            assertNotNull(result == null ? "completed" : result);
        }
    }
}
