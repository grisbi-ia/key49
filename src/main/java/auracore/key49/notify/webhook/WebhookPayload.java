package auracore.key49.notify.webhook;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Payload JSON enviado al webhook endpoint del tenant.
 *
 * @param event tipo de evento (e.g. "document.authorized", "document.rejected")
 * @param documentId UUID del documento
 * @param documentType tipo de comprobante SRI (e.g. "01" para factura)
 * @param accessKey clave de acceso de 49 dígitos
 * @param status estado actual del documento
 * @param issueDate fecha de emisión
 * @param totalAmount monto total
 * @param recipientId identificación del receptor
 * @param recipientName nombre del receptor
 * @param authorizationNumber número de autorización SRI (si aplica)
 * @param authorizationDate fecha/hora de autorización SRI (si aplica)
 * @param timestamp momento de generación del evento
 */
public record WebhookPayload(
        String event,
        String documentId,
        String documentType,
        String accessKey,
        String status,
        LocalDate issueDate,
        BigDecimal totalAmount,
        String recipientId,
        String recipientName,
        String authorizationNumber,
        Instant authorizationDate,
        Instant timestamp
        ) {

}
