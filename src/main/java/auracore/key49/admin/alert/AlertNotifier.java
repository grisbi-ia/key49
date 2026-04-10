package auracore.key49.admin.alert;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Notificador de alertas operativas vía email y webhook.
 *
 * <p>
 * Envía notificaciones cuando una alerta cambia de estado (FIRING/RESOLVED) o
 * como reminder periódico mientras la alerta sigue activa.</p>
 */
@ApplicationScoped
public class AlertNotifier {

    private static final Logger log = Logger.getLogger(AlertNotifier.class);

    @Inject
    ReactiveMailer mailer;

    @ConfigProperty(name = "key49.alerts.email-recipients", defaultValue = "")
    Optional<String> emailRecipients;

    @ConfigProperty(name = "key49.alerts.webhook-url", defaultValue = "")
    Optional<String> webhookUrl;

    @ConfigProperty(name = "key49.alerts.webhook-secret", defaultValue = "")
    Optional<String> webhookSecret;

    @ConfigProperty(name = "key49.email.from", defaultValue = "facturacion@key49.ec")
    String fromAddress;

    /**
     * Envía notificación de alerta disparada (FIRING).
     */
    public void notifyFiring(AlertResult result) {
        var subject = "⚠️ Key49 — Alerta: " + humanName(result.name());
        var body = """
                ALERTA ACTIVA
                
                Condición: %s
                Detalle: %s
                Hora: %s
                
                Esta alerta permanecerá activa hasta que la condición se resuelva.
                
                — Key49 Alertas
                """.formatted(humanName(result.name()), result.summary(), Instant.now());

        sendEmail(subject, body);
        sendWebhook("alert.firing", result);
        log.warnf("Alert FIRING: %s — %s", result.name(), result.summary());
    }

    /**
     * Envía notificación de alerta resuelta (RESOLVED).
     */
    public void notifyResolved(AlertResult result) {
        var subject = "✅ Key49 — Resuelta: " + humanName(result.name());
        var body = """
                ALERTA RESUELTA
                
                Condición: %s
                Detalle: %s
                Hora: %s
                
                La condición ha vuelto a la normalidad.
                
                — Key49 Alertas
                """.formatted(humanName(result.name()), result.summary(), Instant.now());

        sendEmail(subject, body);
        sendWebhook("alert.resolved", result);
        log.infof("Alert RESOLVED: %s — %s", result.name(), result.summary());
    }

    /**
     * Envía reminder de alerta que sigue activa.
     */
    public void notifyReminder(AlertResult result, Duration activeSince) {
        var subject = "⚠️ Key49 — Reminder: " + humanName(result.name());
        var hours = activeSince.toHours();
        var body = """
                ALERTA ACTIVA (REMINDER)
                
                Condición: %s
                Detalle: %s
                Activa desde hace: %d hora(s)
                Hora: %s
                
                Esta alerta sigue sin resolverse.
                
                — Key49 Alertas
                """.formatted(humanName(result.name()), result.summary(), hours, Instant.now());

        sendEmail(subject, body);
        sendWebhook("alert.reminder", result);
        log.warnf("Alert REMINDER: %s — active for %dh", result.name(), hours);
    }

    private void sendEmail(String subject, String body) {
        var recipients = emailRecipients.orElse("");
        if (recipients.isBlank()) {
            return;
        }

        for (var email : recipients.split("[;,]")) {
            var trimmed = email.trim();
            if (trimmed.isBlank()) {
                continue;
            }

            mailer.send(Mail.withText(trimmed, subject, body)
                    .setFrom("Key49 Alertas <" + fromAddress + ">"))
                    .subscribe().with(
                            v -> log.debugf("Alert email sent to %s", trimmed),
                            e -> log.errorf(e, "Failed to send alert email to %s", trimmed)
                    );
        }
    }

    private void sendWebhook(String event, AlertResult result) {
        var url = webhookUrl.orElse("");
        if (url.isBlank()) {
            return;
        }

        var payload = """
                {"event":"%s","alert":"%s","summary":"%s","firing":%b,"timestamp":"%s"}"""
                .formatted(event, result.name(), escapeJson(result.summary()),
                        result.firing(), Instant.now());

        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Key49-Event", event)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));

            var secret = webhookSecret.orElse("");
            if (!secret.isBlank()) {
                requestBuilder.header("X-Key49-Signature", "sha256=" + hmac(payload, secret));
            }

            client.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenAccept(r -> log.debugf("Alert webhook sent: event=%s status=%d", event, r.statusCode()))
                    .exceptionally(e -> {
                        log.errorf(e, "Failed to send alert webhook: event=%s url=%s", event, url);
                        return null;
                    });
        } catch (Exception e) {
            log.errorf(e, "Failed to dispatch alert webhook: event=%s", event);
        }
    }

    private static String hmac(String payload, String secret) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.errorf(e, "Failed to compute HMAC for alert webhook");
            return "";
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String humanName(String alertName) {
        return switch (alertName) {
            case "sri_health" ->
                "SRI no responde";
            case "dlq_messages" ->
                "DLQ con mensajes";
            case "cert_expiry" ->
                "Certificado por vencer";
            case "error_rate" ->
                "Tasa de error alta";
            case "queue_depth" ->
                "Cola saturada";
            case "sla_authorization" ->
                "SLA autorización incumplido";
            default ->
                alertName;
        };
    }
}
