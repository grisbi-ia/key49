package auracore.key49.admin.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class QueueDepthMetricsTest {

    SimpleMeterRegistry registry;
    QueueDepthMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new QueueDepthMetrics(registry);
    }

    @Test
    void shouldRegisterGaugeForEachQueue() {
        for (var queue : QueueDepthMetrics.QUEUES) {
            var shortName = queue.replace("key49.", "");
            var gauge = registry.find("key49.queue.depth").tag("queue", shortName).gauge();
            assertNotNull(gauge, "Gauge missing for queue: " + shortName);
        }
    }

    @Test
    void shouldInitializeGaugesAtZero() {
        for (var queue : QueueDepthMetrics.QUEUES) {
            var shortName = queue.replace("key49.", "");
            var gauge = registry.find("key49.queue.depth").tag("queue", shortName).gauge();
            assertEquals(0.0, gauge.value());
        }
    }

    @Test
    void shouldIncludeDlqQueue() {
        var gauge = registry.find("key49.queue.depth").tag("queue", "dlq").gauge();
        assertNotNull(gauge, "DLQ gauge should be registered");
    }

    @Test
    void shouldHaveFiveGauges() {
        var gauges = registry.find("key49.queue.depth").gauges();
        assertEquals(5, gauges.size());
    }

    @Test
    void shouldRegisterSignQueue() {
        var gauge = registry.find("key49.queue.depth").tag("queue", "sign").gauge();
        assertNotNull(gauge);
    }

    @Test
    void shouldRegisterSendQueue() {
        var gauge = registry.find("key49.queue.depth").tag("queue", "send").gauge();
        assertNotNull(gauge);
    }

    @Test
    void shouldRegisterAuthorizeQueue() {
        var gauge = registry.find("key49.queue.depth").tag("queue", "authorize").gauge();
        assertNotNull(gauge);
    }

    @Test
    void shouldRegisterNotifyQueue() {
        var gauge = registry.find("key49.queue.depth").tag("queue", "notify").gauge();
        assertNotNull(gauge);
    }
}
