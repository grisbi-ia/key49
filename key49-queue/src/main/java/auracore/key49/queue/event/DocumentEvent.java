package auracore.key49.queue.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento que representa una acción sobre un documento en el pipeline de procesamiento.
 * Se publica a RabbitMQ y se consume por los workers correspondientes.
 */
public record DocumentEvent(
        UUID documentId,
        String tenantSchemaName,
        String eventType,
        int retryCount,
        Instant timestamp
) {

    public static DocumentEvent of(UUID documentId, String tenantSchemaName, String eventType) {
        return new DocumentEvent(documentId, tenantSchemaName, eventType, 0, Instant.now());
    }

    public static DocumentEvent ofRetry(UUID documentId, String tenantSchemaName, String eventType, int retryCount) {
        return new DocumentEvent(documentId, tenantSchemaName, eventType, retryCount, Instant.now());
    }

    public DocumentEvent withRetryCount(int retryCount) {
        return new DocumentEvent(documentId, tenantSchemaName, eventType, retryCount, Instant.now());
    }
}
