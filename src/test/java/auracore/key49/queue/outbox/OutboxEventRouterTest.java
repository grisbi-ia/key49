package auracore.key49.queue.outbox;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.queue.producer.DocumentEventProducer;

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
        doNothing().when(producer).sendToSign(any());

        router.route(event);

        verify(producer).sendToSign(event);
    }

    @Test
    void shouldRouteSendEventToSendChannel() {
        var event = DocumentEvent.of(UUID.randomUUID(), "tenant_test", "doc.send");
        doNothing().when(producer).sendToSend(any());

        router.route(event);

        verify(producer).sendToSend(event);
    }

    @Test
    void shouldRouteAuthorizeEventToAuthorizeChannel() {
        var event = DocumentEvent.of(UUID.randomUUID(), "tenant_test", "doc.authorize");
        doNothing().when(producer).sendToAuthorize(any());

        router.route(event);

        verify(producer).sendToAuthorize(event);
    }

    @Test
    void shouldRouteNotifyEventToNotifyChannel() {
        var event = DocumentEvent.of(UUID.randomUUID(), "tenant_test", "doc.notify");
        doNothing().when(producer).sendToNotify(any());

        router.route(event);

        verify(producer).sendToNotify(event);
    }

    @Test
    void shouldFailForUnknownEventType() {
        var event = DocumentEvent.of(UUID.randomUUID(), "tenant_test", "doc.unknown");

        assertThrows(IllegalArgumentException.class, () -> router.route(event));

        verifyNoInteractions(producer);
    }

    @Test
    void shouldPropagateProducerFailure() {
        var event = DocumentEvent.of(UUID.randomUUID(), "tenant_test", "doc.sign");
        doThrow(new RuntimeException("RabbitMQ down")).when(producer).sendToSign(any());

        assertThrows(RuntimeException.class, () -> router.route(event));
    }

    @Test
    void shouldRouteAllFourEventTypes() {
        doNothing().when(producer).sendToSign(any());
        doNothing().when(producer).sendToSend(any());
        doNothing().when(producer).sendToAuthorize(any());
        doNothing().when(producer).sendToNotify(any());

        var types = new String[]{"doc.sign", "doc.send", "doc.authorize", "doc.notify"};
        for (var type : types) {
            var event = DocumentEvent.of(UUID.randomUUID(), "tenant_test", type);
            assertDoesNotThrow(() -> router.route(event));
        }
    }
}
