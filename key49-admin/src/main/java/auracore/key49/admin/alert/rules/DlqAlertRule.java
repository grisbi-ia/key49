package auracore.key49.admin.alert.rules;

import auracore.key49.admin.alert.AlertResult;
import auracore.key49.admin.alert.AlertRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Regla de alerta: DLQ con mensajes.
 *
 * <p>Consulta la cola {@code key49.dlq} vía RabbitMQ Management HTTP API.
 * Dispara alerta si hay más de {@code key49.alerts.dlq-threshold} mensajes.</p>
 */
@ApplicationScoped
public class DlqAlertRule implements AlertRule {

    static final String NAME = "dlq_messages";
    private static final Logger log = Logger.getLogger(DlqAlertRule.class);

    @ConfigProperty(name = "key49.alerts.dlq-threshold", defaultValue = "0")
    int dlqThreshold;

    @ConfigProperty(name = "key49.alerts.rabbitmq-api-url",
            defaultValue = "http://localhost:15672/api/queues/%2F/key49.dlq")
    String rabbitmqApiUrl;

    @ConfigProperty(name = "rabbitmq-username", defaultValue = "guest")
    String rabbitmqUser;

    @ConfigProperty(name = "rabbitmq-password", defaultValue = "guest")
    String rabbitmqPassword;

    @Override
    public AlertResult evaluate() {
        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            var auth = java.util.Base64.getEncoder()
                    .encodeToString((rabbitmqUser + ":" + rabbitmqPassword)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(rabbitmqApiUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Basic " + auth)
                    .GET()
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warnf("RabbitMQ API returned status %d", response.statusCode());
                return AlertResult.ok(NAME, "No se pudo consultar DLQ (HTTP " + response.statusCode() + ")");
            }

            var messageCount = extractMessageCount(response.body());

            if (messageCount > dlqThreshold) {
                return AlertResult.firing(NAME,
                        "DLQ tiene %d mensaje(s) (umbral: %d)".formatted(messageCount, dlqThreshold));
            }

            return AlertResult.ok(NAME, "DLQ vacía (%d mensajes)".formatted(messageCount));
        } catch (Exception e) {
            log.warnf("DLQ alert eval failed: %s", e.getMessage());
            return AlertResult.ok(NAME, "No se pudo evaluar: " + e.getMessage());
        }
    }

    /**
     * Extrae el campo "messages" del JSON de la API de RabbitMQ.
     * Parsing simple sin dependencia adicional de JSON.
     */
    static int extractMessageCount(String json) {
        // Look for "messages": followed by a number (not "messages_ready" or "messages_unacknowledged")
        var pattern = java.util.regex.Pattern.compile("\"messages\"\\s*:\\s*(\\d+)");
        var matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }
}
