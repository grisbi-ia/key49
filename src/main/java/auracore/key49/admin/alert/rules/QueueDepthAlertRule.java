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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Regla de alerta: Colas saturadas.
 *
 * <p>Consulta las colas {@code key49.sign}, {@code key49.send},
 * {@code key49.authorize}, {@code key49.notify} vía RabbitMQ Management API.
 * Dispara alerta si alguna cola tiene más de {@code key49.alerts.queue-depth-max} mensajes.</p>
 */
@ApplicationScoped
public class QueueDepthAlertRule implements AlertRule {

    static final String NAME = "queue_depth";
    private static final Logger log = Logger.getLogger(QueueDepthAlertRule.class);

    static final List<String> QUEUES = List.of(
            "key49.sign", "key49.send", "key49.authorize", "key49.notify");

    @ConfigProperty(name = "key49.alerts.queue-depth-max", defaultValue = "1000")
    int queueDepthMax;

    @ConfigProperty(name = "key49.alerts.rabbitmq-api-base-url",
            defaultValue = "http://localhost:15672/api/queues/%2F")
    String rabbitmqApiBaseUrl;

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
                            .getBytes(StandardCharsets.UTF_8));

            var overloaded = new ArrayList<String>();

            for (var queue : QUEUES) {
                var count = getQueueMessageCount(client, auth, queue);
                if (count > queueDepthMax) {
                    overloaded.add("%s (%d msgs)".formatted(queue, count));
                }
            }

            if (!overloaded.isEmpty()) {
                return AlertResult.firing(NAME,
                        "%d cola(s) saturada(s): %s (umbral: %d)".formatted(
                                overloaded.size(), String.join(", ", overloaded), queueDepthMax));
            }

            return AlertResult.ok(NAME,
                    "Todas las colas bajo el umbral de %d mensajes".formatted(queueDepthMax));
        } catch (Exception e) {
            log.warnf("Queue depth alert eval failed: %s", e.getMessage());
            return AlertResult.ok(NAME, "No se pudo evaluar: " + e.getMessage());
        }
    }

    private int getQueueMessageCount(HttpClient client, String auth, String queueName) {
        try {
            var baseUrl = rabbitmqApiBaseUrl.endsWith("/") ? rabbitmqApiBaseUrl : rabbitmqApiBaseUrl + "/";
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + queueName))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Basic " + auth)
                    .GET()
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return DlqAlertRule.extractMessageCount(response.body());
            }
            return 0;
        } catch (Exception e) {
            log.debugf("Could not check queue %s: %s", queueName, e.getMessage());
            return 0;
        }
    }
}
