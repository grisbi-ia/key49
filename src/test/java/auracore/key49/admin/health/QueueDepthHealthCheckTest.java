package auracore.key49.admin.health;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.microprofile.health.HealthCheckResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueDepthHealthCheckTest {

    QueueDepthHealthCheck healthCheck;

    @BeforeEach
    void setUp() {
        healthCheck = new StubQueueDepthHealthCheck(Map.of(
                "key49.sign", 10,
                "key49.send", 5,
                "key49.authorize", 3,
                "key49.notify", 0));
        healthCheck.criticalThreshold = 5000;
        healthCheck.warningThreshold = 1000;
    }

    @Test
    void shouldReturnUpWhenAllQueuesBelowThreshold() {
        var response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals("RabbitMQ queue depth", response.getName());
    }

    @Test
    void shouldIncludeQueueDepthsInData() {
        var response = healthCheck.call();
        var data = response.getData().orElseThrow();

        assertEquals(10L, data.get("key49.sign"));
        assertEquals(5L, data.get("key49.send"));
        assertEquals(3L, data.get("key49.authorize"));
        assertEquals(0L, data.get("key49.notify"));
    }

    @Test
    void shouldIncludeThresholdsInData() {
        var response = healthCheck.call();
        var data = response.getData().orElseThrow();

        assertEquals(5000L, data.get("critical_threshold"));
        assertEquals(1000L, data.get("warning_threshold"));
    }

    @Test
    void shouldReturnDownWhenQueueExceedsCriticalThreshold() {
        healthCheck = new StubQueueDepthHealthCheck(Map.of(
                "key49.sign", 10,
                "key49.send", 6000,
                "key49.authorize", 3,
                "key49.notify", 0));
        healthCheck.criticalThreshold = 5000;
        healthCheck.warningThreshold = 1000;

        var response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }

    @Test
    void shouldReturnDownWhenMultipleQueuesExceedThreshold() {
        healthCheck = new StubQueueDepthHealthCheck(Map.of(
                "key49.sign", 7000,
                "key49.send", 5001,
                "key49.authorize", 3,
                "key49.notify", 0));
        healthCheck.criticalThreshold = 5000;
        healthCheck.warningThreshold = 1000;

        var response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }

    @Test
    void shouldReturnUpAtExactThreshold() {
        healthCheck = new StubQueueDepthHealthCheck(Map.of(
                "key49.sign", 5000,
                "key49.send", 0,
                "key49.authorize", 0,
                "key49.notify", 0));
        healthCheck.criticalThreshold = 5000;
        healthCheck.warningThreshold = 1000;

        var response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    }

    @Test
    void shouldReturnUpOnApiError() {
        healthCheck = new ErrorQueueDepthHealthCheck();
        healthCheck.criticalThreshold = 5000;
        healthCheck.warningThreshold = 1000;

        var response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertTrue(response.getData().orElseThrow().containsKey("error"));
    }

    @Test
    void shouldRespectCustomCriticalThreshold() {
        healthCheck = new StubQueueDepthHealthCheck(Map.of(
                "key49.sign", 150,
                "key49.send", 0,
                "key49.authorize", 0,
                "key49.notify", 0));
        healthCheck.criticalThreshold = 100;
        healthCheck.warningThreshold = 50;

        var response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }

    static class StubQueueDepthHealthCheck extends QueueDepthHealthCheck {

        private final Map<String, Integer> depths;

        StubQueueDepthHealthCheck(Map<String, Integer> depths) {
            this.depths = new LinkedHashMap<>(depths);
        }

        @Override
        Map<String, Integer> fetchQueueDepths() {
            return depths;
        }
    }

    static class ErrorQueueDepthHealthCheck extends QueueDepthHealthCheck {

        @Override
        Map<String, Integer> fetchQueueDepths() {
            throw new RuntimeException("RabbitMQ management API unreachable");
        }
    }
}
