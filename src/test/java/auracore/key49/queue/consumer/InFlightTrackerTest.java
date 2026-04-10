package auracore.key49.queue.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


class InFlightTrackerTest {

    InFlightTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new InFlightTracker();
    }

    @Test
    void shouldStartWithZeroTotal() {
        assertEquals(0, tracker.totalInFlight());
        assertTrue(tracker.snapshot().isEmpty());
    }

    @Test
    void shouldTrackIncrements() {
        tracker.increment("SignConsumer");
        tracker.increment("SignConsumer");
        tracker.increment("SendConsumer");

        assertEquals(3, tracker.totalInFlight());

        var snapshot = tracker.snapshot();
        assertEquals(2, snapshot.get("SignConsumer"));
        assertEquals(1, snapshot.get("SendConsumer"));
    }

    @Test
    void shouldTrackDecrements() {
        tracker.increment("SignConsumer");
        tracker.increment("SignConsumer");
        tracker.decrement("SignConsumer");

        assertEquals(1, tracker.totalInFlight());
        assertEquals(1, tracker.snapshot().get("SignConsumer"));
    }

    @Test
    void shouldReturnZeroAfterFullDrain() {
        tracker.increment("NotifyConsumer");
        tracker.increment("NotifyConsumer");
        tracker.decrement("NotifyConsumer");
        tracker.decrement("NotifyConsumer");

        assertEquals(0, tracker.totalInFlight());
    }

    @Test
    void shouldHandleDecrementOnUnknownConsumer() {
        // decrement on a consumer that was never incremented should not throw
        tracker.decrement("UnknownConsumer");
        assertEquals(0, tracker.totalInFlight());
    }

    @Test
    void shouldReturnImmutableSnapshot() {
        tracker.increment("SignConsumer");
        var snapshot = tracker.snapshot();

        // Modifications after snapshot should not affect the returned map
        tracker.increment("SignConsumer");
        assertEquals(1, snapshot.get("SignConsumer"));
        assertEquals(2, tracker.snapshot().get("SignConsumer"));
    }

    @Test
    void shouldTrackAllFiveConsumers() {
        tracker.increment("SignConsumer");
        tracker.increment("SendConsumer");
        tracker.increment("AuthorizeConsumer");
        tracker.increment("NotifyConsumer");
        tracker.increment("DlqConsumer");

        assertEquals(5, tracker.totalInFlight());
        assertEquals(5, tracker.snapshot().size());
    }
}
