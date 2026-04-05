package auracore.key49.queue.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import auracore.key49.core.model.OutboxEvent;
import auracore.key49.queue.event.DocumentEvent;

class OutboxEventTest {

    @Test
    void shouldCreateOutboxEventWithCorrectDefaults() {
        var docId = UUID.randomUUID();
        var event = OutboxEvent.create(docId, "doc.sign", "{}");

        assertEquals("documents", event.aggregateType);
        assertEquals(docId, event.aggregateId);
        assertEquals("doc.sign", event.eventType);
        assertEquals("{}", event.payload);
        assertFalse(event.published);
        assertNotNull(event.createdAt);
    }

    @Test
    void shouldCreateDocumentEventFromOutbox() {
        var docId = UUID.randomUUID();
        var outbox = OutboxEvent.create(docId, "doc.send", "{\"key\":\"value\"}");

        var docEvent = DocumentEvent.fromOutbox(
                outbox.aggregateId, "tenant_test", outbox.eventType, outbox.payload);

        assertEquals(docId, docEvent.documentId());
        assertEquals("tenant_test", docEvent.tenantSchemaName());
        assertEquals("doc.send", docEvent.eventType());
        assertEquals(0, docEvent.retryCount());
        assertNotNull(docEvent.timestamp());
    }

    @Test
    void shouldSupportAllPipelineEventTypes() {
        var docId = UUID.randomUUID();
        var types = new String[]{"doc.sign", "doc.send", "doc.authorize", "doc.notify"};

        for (var type : types) {
            var event = OutboxEvent.create(docId, type, "{}");
            assertEquals(type, event.eventType);
            assertEquals("documents", event.aggregateType);
        }
    }

    @Test
    void shouldCreateEventWithEmptyPayload() {
        var event = OutboxEvent.create(UUID.randomUUID(), "doc.sign", "{}");
        assertEquals("{}", event.payload);
    }

    @Test
    void shouldCreateEventWithJsonPayload() {
        var payload = "{\"signedXml\":\"<factura/>\"}";
        var event = OutboxEvent.create(UUID.randomUUID(), "doc.send", payload);
        assertEquals(payload, event.payload);
    }

    @Test
    void shouldNotBePublishedByDefault() {
        var event = OutboxEvent.create(UUID.randomUUID(), "doc.sign", "{}");
        assertFalse(event.published);
        assertTrue(event.publishedAt == null);
    }
}
