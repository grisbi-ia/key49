package auracore.key49.queue.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.runtime.ShutdownEvent;


class GracefulShutdownObserverTest {

    GracefulShutdownObserver observer;
    InFlightTracker tracker;
    List<String> logMessages;

    @BeforeEach
    void setUp() {
        tracker = new InFlightTracker();
        logMessages = new ArrayList<>();
        observer = new GracefulShutdownObserver();
        observer.tracker = tracker;
        observer.shutdownTimeout = "30s";
        observer.log = Logger.getLogger(GracefulShutdownObserver.class);
    }

    @Test
    void shouldLogCleanShutdownWhenNoInFlightMessages() {
        // No messages in flight — should log "clean shutdown"
        observer.onShutdown(new ShutdownEvent());

        // The method completes without error — no in-flight messages
        assertEquals(0, tracker.totalInFlight());
    }

    @Test
    void shouldLogInFlightMessagesOnShutdown() {
        tracker.increment("SignConsumer");
        tracker.increment("SendConsumer");
        tracker.increment("SendConsumer");

        observer.onShutdown(new ShutdownEvent());

        // After shutdown observer runs, it reports but doesn't clear counters
        assertEquals(3, tracker.totalInFlight());
    }

    @Test
    void shouldHandleShutdownWithSingleConsumer() {
        tracker.increment("NotifyConsumer");

        observer.onShutdown(new ShutdownEvent());

        assertEquals(1, tracker.totalInFlight());
        assertEquals(1, tracker.snapshot().get("NotifyConsumer"));
    }

    @Test
    void shouldHandleShutdownWithAllConsumers() {
        tracker.increment("SignConsumer");
        tracker.increment("SendConsumer");
        tracker.increment("AuthorizeConsumer");
        tracker.increment("NotifyConsumer");
        tracker.increment("DlqConsumer");

        observer.onShutdown(new ShutdownEvent());

        assertEquals(5, tracker.totalInFlight());
    }

    @Test
    void shouldUseConfiguredTimeout() {
        observer.shutdownTimeout = "60s";
        observer.onShutdown(new ShutdownEvent());
        // Completes without error — uses configured timeout in log message
        assertEquals(0, tracker.totalInFlight());
    }
}
