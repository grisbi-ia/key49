package auracore.key49.queue.event;

import java.time.Instant;
import java.util.UUID;

import io.vertx.core.json.JsonObject;

/**
 * Evento que representa una acción sobre un documento en el pipeline de
 * procesamiento. Se publica a RabbitMQ y se consume por los workers
 * correspondientes.
 */
public record DocumentEvent(
        UUID documentId,
        String tenantSchemaName,
        String eventType,
        int retryCount,
        Instant timestamp
        ) {

    /**
     * Convierte un JsonObject de RabbitMQ a DocumentEvent. SmallRye RabbitMQ
     * entrega los mensajes como JsonObject, no como POJOs. Los campos llegan en
     * snake_case por la configuración global
     * quarkus.jackson.property-naming-strategy=SNAKE_CASE.
     */
    public static DocumentEvent fromJson(JsonObject json) {
        return new DocumentEvent(
                UUID.fromString(json.getString("document_id")),
                json.getString("tenant_schema_name"),
                json.getString("event_type"),
                json.getInteger("retry_count", 0),
                Instant.parse(json.getString("timestamp"))
        );
    }

    public static DocumentEvent of(UUID documentId, String tenantSchemaName, String eventType) {
        return new DocumentEvent(documentId, tenantSchemaName, eventType, 0, Instant.now());
    }

    public static DocumentEvent ofRetry(UUID documentId, String tenantSchemaName, String eventType, int retryCount) {
        return new DocumentEvent(documentId, tenantSchemaName, eventType, retryCount, Instant.now());
    }

    public static DocumentEvent fromOutbox(UUID aggregateId, String tenantSchemaName, String eventType, String payload) {
        return new DocumentEvent(aggregateId, tenantSchemaName, eventType, 0, Instant.now());
    }

    public DocumentEvent withRetryCount(int retryCount) {
        return new DocumentEvent(documentId, tenantSchemaName, eventType, retryCount, Instant.now());
    }
}
