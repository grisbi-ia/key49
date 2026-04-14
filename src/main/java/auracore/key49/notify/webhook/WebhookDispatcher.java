package auracore.key49.notify.webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.WebhookDelivery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Dispatcher de webhooks con firma HMAC-SHA256.
 *
 * <p>
 * Envía un HTTP POST al webhook_url del tenant con el payload del evento,
 * incluyendo la firma HMAC-SHA256 en el header {@code X-Key49-Signature}. Si
 * falla, registra el intento y calcula el siguiente reintento con backoff: 10s,
 * 60s, 300s.</p>
 *
 * <p>
 * Este servicio es bloqueante (usa java.net.http.HttpClient síncrono). Debe
 * invocarse desde un contexto que soporte operaciones bloqueantes.</p>
 */
@ApplicationScoped
public class WebhookDispatcher {

    private static final Logger log = Logger.getLogger(WebhookDispatcher.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_HEADER = "X-Key49-Signature";
    private static final String EVENT_HEADER = "X-Key49-Event";
    private static final String DELIVERY_HEADER = "X-Key49-Delivery";

    /**
     * Delays de reintento en segundos: 10s, 60s, 300s.
     */
    static final long[] RETRY_DELAYS_SECONDS = {10, 60, 300};

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "key49.webhook.connect-timeout-ms", defaultValue = "5000")
    int connectTimeoutMs;

    @ConfigProperty(name = "key49.webhook.read-timeout-ms", defaultValue = "10000")
    int readTimeoutMs;

    @ConfigProperty(name = "key49.webhook.enabled", defaultValue = "true")
    boolean webhookEnabled;

    @ConfigProperty(name = "key49.webhook.ssrf-validation", defaultValue = "true")
    boolean ssrfValidationEnabled;

    /**
     * Construye el payload del webhook a partir de un documento y evento.
     */
    public WebhookPayload buildPayload(Document doc, String eventType) {
        return new WebhookPayload(
                eventType,
                doc.id.toString(),
                doc.documentType,
                doc.accessKey,
                doc.status.name(),
                doc.issueDate,
                doc.totalAmount,
                doc.recipientId,
                doc.recipientName,
                doc.authorizationNumber,
                doc.authorizationDate,
                Instant.now()
        );
    }

    /**
     * Serializa el payload a JSON.
     */
    public String serializePayload(WebhookPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new WebhookException("Failed to serialize webhook payload", e);
        }
    }

    /**
     * Crea un registro de entrega y despacha el webhook. Retorna el
     * WebhookDelivery con el resultado del envío.
     *
     * @param webhookUrl URL del endpoint del tenant
     * @param webhookSecret secret HMAC del tenant
     * @param doc documento origen
     * @param eventType tipo de evento (e.g. "document.authorized")
     * @return WebhookDelivery con resultado del envío
     */
    public WebhookDelivery dispatch(String webhookUrl, String webhookSecret, Document doc, String eventType) {
        if (!webhookEnabled) {
            log.infof("Webhooks disabled, skipping dispatch for document %s", doc.id);
            return null;
        }

        var payload = buildPayload(doc, eventType);
        var json = serializePayload(payload);
        var delivery = WebhookDelivery.create(doc.id, eventType, webhookUrl, json);

        send(delivery, webhookUrl, webhookSecret, json);
        return delivery;
    }

    /**
     * Reintenta enviar un webhook pendiente.
     *
     * @param delivery registro de entrega existente
     * @param webhookSecret secret HMAC del tenant
     */
    public void retry(WebhookDelivery delivery, String webhookSecret) {
        delivery.attempt++;
        send(delivery, delivery.url, webhookSecret, delivery.requestBody);
    }

    /**
     * Ejecuta el envío HTTP POST del webhook.
     */
    void send(WebhookDelivery delivery, String webhookUrl, String webhookSecret, String json) {
        if (ssrfValidationEnabled) {
            WebhookUrlValidator.validate(webhookUrl);
        }
        long startTime = System.currentTimeMillis();

        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("Content-Type", "application/json")
                    .header(EVENT_HEADER, delivery.eventType)
                    .header(DELIVERY_HEADER, delivery.id != null ? delivery.id.toString() : "");

            if (webhookSecret != null && !webhookSecret.isBlank()) {
                var signature = computeSignature(json, webhookSecret);
                requestBuilder.header(SIGNATURE_HEADER, "sha256=" + signature);
            }

            var request = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int durationMs = (int) (System.currentTimeMillis() - startTime);

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                delivery.markDelivered(response.statusCode(), response.body(), durationMs);
                log.infof("Webhook delivered: document=%s, event=%s, status=%d, duration=%dms",
                        delivery.documentId, delivery.eventType, response.statusCode(), durationMs);
            } else {
                var nextRetry = calculateNextRetryAt(delivery.attempt);
                delivery.markFailed(response.statusCode(), response.body(), durationMs, nextRetry);
                log.warnf("Webhook failed: document=%s, event=%s, status=%d, attempt=%d/%d",
                        delivery.documentId, delivery.eventType, response.statusCode(),
                        delivery.attempt, delivery.maxAttempts);
            }
        } catch (Exception e) {
            int durationMs = (int) (System.currentTimeMillis() - startTime);
            var nextRetry = calculateNextRetryAt(delivery.attempt);
            delivery.markFailed(null, e.getMessage(), durationMs, nextRetry);
            log.warnf("Webhook error: document=%s, event=%s, error=%s, attempt=%d/%d",
                    delivery.documentId, delivery.eventType, e.getMessage(),
                    delivery.attempt, delivery.maxAttempts);
        }
    }

    /**
     * Calcula la firma HMAC-SHA256 del body con el secret del tenant.
     *
     * @param body payload JSON
     * @param secret clave secreta del tenant
     * @return firma hexadecimal
     */
    public String computeSignature(String body, String secret) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            var keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            var hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new WebhookException("Failed to compute HMAC-SHA256 signature", e);
        }
    }

    /**
     * Calcula el próximo intento con backoff: 10s, 60s, 300s.
     */
    static Instant calculateNextRetryAt(int currentAttempt) {
        int index = Math.min(currentAttempt - 1, RETRY_DELAYS_SECONDS.length - 1);
        return Instant.now().plusSeconds(RETRY_DELAYS_SECONDS[Math.max(0, index)]);
    }
}
