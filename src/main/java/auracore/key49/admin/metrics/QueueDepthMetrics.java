package auracore.key49.admin.metrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import auracore.key49.admin.alert.rules.DlqAlertRule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Expone métricas de profundidad de colas RabbitMQ en Prometheus vía
 * Micrometer.
 *
 * <p>
 * Registra un gauge {@code key49.queue.depth} con tag {@code queue} para cada
 * cola monitoreada. El valor se actualiza cada 30 segundos consultando la API
 * de management de RabbitMQ.</p>
 */

@ApplicationScoped
public class QueueDepthMetrics {

    private static final Logger log = Logger.getLogger(QueueDepthMetrics.class);

    static final List<String> QUEUES = List.of(
            "key49.sign", "key49.send", "key49.authorize", "key49.notify", "key49.dlq");

    private final Map<String, AtomicInteger> gaugeValues = new ConcurrentHashMap<>();

    @ConfigProperty(name = "key49.alerts.rabbitmq-api-base-url",
            defaultValue = "http://localhost:15672/api/queues/%2F")
    String rabbitmqApiBaseUrl;

    @ConfigProperty(name = "rabbitmq-username", defaultValue = "guest")
    String rabbitmqUser;

    @ConfigProperty(name = "rabbitmq-password", defaultValue = "guest")
    String rabbitmqPassword;

    @Inject
    public QueueDepthMetrics(MeterRegistry registry) {
        for (var queue : QUEUES) {
            var gauge = new AtomicInteger(0);
            gaugeValues.put(queue, gauge);
            var shortName = queue.replace("key49.", "");
            io.micrometer.core.instrument.Gauge.builder("key49.queue.depth", gauge, AtomicInteger::get)
                    .tags(Tags.of("queue", shortName))
                    .description("Current message count in RabbitMQ queue")
                    .register(registry);
        }
    }

    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refreshDepths() {
        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();

            var auth = java.util.Base64.getEncoder()
                    .encodeToString((rabbitmqUser + ":" + rabbitmqPassword)
                            .getBytes(StandardCharsets.UTF_8));

            var baseUrl = rabbitmqApiBaseUrl.endsWith("/") ? rabbitmqApiBaseUrl : rabbitmqApiBaseUrl + "/";

            for (var queue : QUEUES) {
                var depth = fetchDepth(client, auth, baseUrl, queue);
                gaugeValues.get(queue).set(depth);
            }
        } catch (Exception e) {
            log.debugf("Failed to refresh queue depths: %s", e.getMessage());
        }
    }

    private int fetchDepth(HttpClient client, String auth, String baseUrl, String queueName) {
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
            log.debugf("Could not fetch depth for queue %s: %s", queueName, e.getMessage());
            return 0;
        }
    }
}
