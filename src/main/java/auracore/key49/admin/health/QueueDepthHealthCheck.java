package auracore.key49.admin.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

import auracore.key49.admin.alert.rules.DlqAlertRule;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Health check de readiness que monitorea la profundidad de las colas RabbitMQ.
 *
 * <p>
 * Si alguna cola excede el umbral crítico ({@code key49.queue.depth.critical}),
 * el health check reporta DOWN y el balanceador deja de enviar tráfico a esta
 * instancia hasta que las colas se drenen.</p>
 */

@Readiness
@ApplicationScoped
public class QueueDepthHealthCheck implements HealthCheck {

    private static final Logger log = Logger.getLogger(QueueDepthHealthCheck.class);

    static final List<String> QUEUES = List.of(
            "key49.sign", "key49.send", "key49.authorize", "key49.notify");

    @ConfigProperty(name = "key49.queue.depth.critical", defaultValue = "5000")
    int criticalThreshold;

    @ConfigProperty(name = "key49.queue.depth.warning", defaultValue = "1000")
    int warningThreshold;

    @ConfigProperty(name = "key49.alerts.rabbitmq-api-base-url",
            defaultValue = "http://localhost:15672/api/queues/%2F")
    String rabbitmqApiBaseUrl;

    @ConfigProperty(name = "rabbitmq-username", defaultValue = "guest")
    String rabbitmqUser;

    @ConfigProperty(name = "rabbitmq-password", defaultValue = "guest")
    String rabbitmqPassword;

    @Override
    public HealthCheckResponse call() {
        try {
            var depths = fetchQueueDepths();
            var builder = HealthCheckResponse.named("RabbitMQ queue depth");

            boolean critical = false;
            for (var entry : depths.entrySet()) {
                builder.withData(entry.getKey(), entry.getValue());
                if (entry.getValue() > criticalThreshold) {
                    critical = true;
                }
            }

            builder.withData("critical_threshold", criticalThreshold);
            builder.withData("warning_threshold", warningThreshold);

            if (critical) {
                log.warnf("Queue depth critical — at least one queue exceeds %d messages", criticalThreshold);
                return builder.down().build();
            }

            return builder.up().build();
        } catch (Exception e) {
            log.warnf("Queue depth health check failed: %s", e.getMessage());
            return HealthCheckResponse.named("RabbitMQ queue depth")
                    .up()
                    .withData("error", e.getMessage())
                    .build();
        }
    }

    Map<String, Integer> fetchQueueDepths() {
        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        var auth = java.util.Base64.getEncoder()
                .encodeToString((rabbitmqUser + ":" + rabbitmqPassword)
                        .getBytes(StandardCharsets.UTF_8));

        var baseUrl = rabbitmqApiBaseUrl.endsWith("/") ? rabbitmqApiBaseUrl : rabbitmqApiBaseUrl + "/";
        var depths = new LinkedHashMap<String, Integer>();

        for (var queue : QUEUES) {
            depths.put(queue, fetchSingleQueueDepth(client, auth, baseUrl, queue));
        }

        return depths;
    }

    private int fetchSingleQueueDepth(HttpClient client, String auth, String baseUrl, String queueName) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + queueName))
                    .timeout(Duration.ofSeconds(3))
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
