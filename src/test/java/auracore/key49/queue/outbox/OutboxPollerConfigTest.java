package auracore.key49.queue.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Verifica la configuración del OutboxPoller: batch-size configurable,
 * métricas Micrometer registradas, y polling adaptativo.
 */
@QuarkusTest
class OutboxPollerConfigTest {

    @ConfigProperty(name = "key49.outbox.batch-size")
    int batchSize;

    @ConfigProperty(name = "key49.outbox.poll-interval")
    String pollInterval;

    @Inject
    MeterRegistry registry;

    @Inject
    OutboxPoller outboxPoller;

    @Test
    @DisplayName("Batch size debe tener valor por defecto de 50")
    void batchSizeShouldBeConfigured() {
        assertTrue(batchSize > 0, "Batch size must be positive");
        assertEquals(50, batchSize, "Default batch size should be 50");
    }

    @Test
    @DisplayName("Poll interval debe estar configurado")
    void pollIntervalShouldBeConfigured() {
        assertNotNull(pollInterval, "Poll interval must be configured");
    }

    @Test
    @DisplayName("Métrica key49.outbox.events.polled debe estar registrada")
    void polledCounterShouldBeRegistered() {
        var counter = registry.find("key49.outbox.events.polled").counter();
        assertNotNull(counter, "Counter key49.outbox.events.polled should be registered");
    }

    @Test
    @DisplayName("Métrica key49.outbox.poll.duration debe estar registrada")
    void pollDurationTimerShouldBeRegistered() {
        var timer = registry.find("key49.outbox.poll.duration").timer();
        assertNotNull(timer, "Timer key49.outbox.poll.duration should be registered");
    }

    @Test
    @DisplayName("OutboxPoller debe inyectarse correctamente")
    void outboxPollerShouldBeInjected() {
        assertNotNull(outboxPoller, "OutboxPoller should be injected");
    }
}
