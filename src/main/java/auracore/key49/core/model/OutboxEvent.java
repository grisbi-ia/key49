package auracore.key49.core.model;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Evento de outbox para garantizar entrega de mensajes a RabbitMQ.
 *
 * <p>Se persiste en la misma transacción que el cambio de estado del documento.
 * Un poller lee los eventos no publicados y los envía a RabbitMQ.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "outbox_id")
    public UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    public String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    public UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    public String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    public String payload;

    @Column(name = "published", nullable = false)
    public boolean published;

    @Column(name = "published_at")
    public Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    /**
     * Crea un evento de outbox para un documento.
     *
     * @param aggregateId ID del documento
     * @param eventType   tipo de evento (doc.sign, doc.send, doc.authorize, doc.notify)
     * @param payload     payload serializado como JSON
     */
    public static OutboxEvent create(UUID aggregateId, String eventType, String payload) {
        var event = new OutboxEvent();
        event.aggregateType = "documents";
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.payload = payload;
        event.published = false;
        event.createdAt = Instant.now();
        return event;
    }
}
