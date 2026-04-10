package auracore.key49.queue.consumer;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Verifica la configuración de graceful shutdown y la integración del observer
 * con el InFlightTracker.
 */
@QuarkusTest
class GracefulShutdownConfigTest {

    @ConfigProperty(name = "quarkus.shutdown.timeout")
    String shutdownTimeout;

    @Inject
    InFlightTracker tracker;

    @Inject
    GracefulShutdownObserver observer;

    @Test
    @DisplayName("Shutdown timeout debe ser 30s para permitir drenaje de consumers")
    void shutdownTimeoutShouldBe30s() {
        assertEquals("30s", shutdownTimeout);
    }

    @Test
    @DisplayName("InFlightTracker debe estar disponible como CDI bean")
    void trackerShouldBeInjectable() {
        assertNotNull(tracker);
        assertEquals(0, tracker.totalInFlight());
    }

    @Test
    @DisplayName("GracefulShutdownObserver debe estar disponible como CDI bean")
    void observerShouldBeInjectable() {
        assertNotNull(observer);
    }

    @Test
    @DisplayName("Tracker debe registrar y desregistrar consumers correctamente")
    void trackerShouldTrackConsumerLifecycle() {
        tracker.increment("TestConsumer");
        assertEquals(1, tracker.totalInFlight());

        tracker.decrement("TestConsumer");
        assertEquals(0, tracker.totalInFlight());
    }
}
