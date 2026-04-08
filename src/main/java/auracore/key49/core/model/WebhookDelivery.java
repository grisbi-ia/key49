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
 * Registro de entrega de webhook. Almacena cada intento de envío HTTP POST
 * al endpoint del tenant.
 */
@Entity
@Table(name = "webhook_deliveries")
public class WebhookDelivery extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "webhook_delivery_id")
    public UUID id;

    @Column(name = "document_id", nullable = false)
    public UUID documentId;

    @Column(name = "event_type", nullable = false, length = 50)
    public String eventType;

    @Column(name = "url", nullable = false, length = 500)
    public String url;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_body", nullable = false, columnDefinition = "jsonb")
    public String requestBody;

    @Column(name = "response_status")
    public Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "text")
    public String responseBody;

    @Column(name = "duration_ms")
    public Integer durationMs;

    @Column(name = "attempt", nullable = false)
    public short attempt = 1;

    @Column(name = "max_attempts", nullable = false)
    public short maxAttempts = 3;

    @Column(name = "next_attempt_at")
    public Instant nextAttemptAt;

    @Column(name = "status", nullable = false, length = 20)
    public String status = "pending";

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    /**
     * Crea un nuevo registro de entrega de webhook en estado pendiente.
     */
    public static WebhookDelivery create(UUID documentId, String eventType, String url, String requestBody) {
        var delivery = new WebhookDelivery();
        delivery.documentId = documentId;
        delivery.eventType = eventType;
        delivery.url = url;
        delivery.requestBody = requestBody;
        delivery.attempt = 1;
        delivery.maxAttempts = 3;
        delivery.status = "pending";
        delivery.createdAt = Instant.now();
        return delivery;
    }

    /**
     * Marca la entrega como exitosa.
     */
    public void markDelivered(int httpStatus, String body, int durationMs) {
        this.responseStatus = httpStatus;
        this.responseBody = truncate(body, 2000);
        this.durationMs = durationMs;
        this.status = "delivered";
        this.nextAttemptAt = null;
    }

    /**
     * Marca la entrega como fallida con posibilidad de reintento.
     */
    public void markFailed(Integer httpStatus, String body, int durationMs, Instant nextRetry) {
        this.responseStatus = httpStatus;
        this.responseBody = truncate(body, 2000);
        this.durationMs = durationMs;
        if (this.attempt >= this.maxAttempts) {
            this.status = "failed";
            this.nextAttemptAt = null;
        } else {
            this.status = "pending";
            this.nextAttemptAt = nextRetry;
        }
    }

    public boolean canRetry() {
        return "pending".equals(this.status) && this.attempt < this.maxAttempts;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
