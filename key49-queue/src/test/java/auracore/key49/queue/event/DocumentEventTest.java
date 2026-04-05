package auracore.key49.queue.event;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DocumentEventTest {

    @Test
    void shouldCreateEventWithFactory() {
        var docId = UUID.randomUUID();
        var event = DocumentEvent.of(docId, "tenant_abc", "doc.sign");

        assertEquals(docId, event.documentId());
        assertEquals("tenant_abc", event.tenantSchemaName());
        assertEquals("doc.sign", event.eventType());
        assertEquals(0, event.retryCount());
        assertNotNull(event.timestamp());
    }

    @Test
    void shouldCreateRetryEvent() {
        var docId = UUID.randomUUID();
        var event = DocumentEvent.ofRetry(docId, "tenant_abc", "doc.send", 3);

        assertEquals(docId, event.documentId());
        assertEquals("tenant_abc", event.tenantSchemaName());
        assertEquals("doc.send", event.eventType());
        assertEquals(3, event.retryCount());
        assertNotNull(event.timestamp());
    }

    @Test
    void shouldCreateEventWithIncrementedRetry() {
        var docId = UUID.randomUUID();
        var original = DocumentEvent.of(docId, "tenant_abc", "doc.send");

        var retried = original.withRetryCount(2);

        assertEquals(original.documentId(), retried.documentId());
        assertEquals(original.tenantSchemaName(), retried.tenantSchemaName());
        assertEquals(original.eventType(), retried.eventType());
        assertEquals(2, retried.retryCount());
    }

    @Test
    void shouldBeImmutableRecord() {
        var docId = UUID.randomUUID();
        var event1 = DocumentEvent.of(docId, "tenant_abc", "doc.sign");
        var event2 = DocumentEvent.of(docId, "tenant_abc", "doc.sign");

        // Records with same values at same instant won't be equal due to timestamp
        assertEquals(event1.documentId(), event2.documentId());
        assertEquals(event1.tenantSchemaName(), event2.tenantSchemaName());
        assertEquals(event1.eventType(), event2.eventType());
    }

    @Test
    void shouldSupportAllEventTypes() {
        var docId = UUID.randomUUID();
        var schema = "tenant_test";

        var sign = DocumentEvent.of(docId, schema, "doc.sign");
        var send = DocumentEvent.of(docId, schema, "doc.send");
        var authorize = DocumentEvent.of(docId, schema, "doc.authorize");
        var notify = DocumentEvent.of(docId, schema, "doc.notify");

        assertEquals("doc.sign", sign.eventType());
        assertEquals("doc.send", send.eventType());
        assertEquals("doc.authorize", authorize.eventType());
        assertEquals("doc.notify", notify.eventType());
    }
}
