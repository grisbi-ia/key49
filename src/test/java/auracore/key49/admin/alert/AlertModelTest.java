package auracore.key49.admin.alert;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Tests unitarios para AlertResult y AlertState.
 */
class AlertModelTest {

    @Test
    void alertResultOkShouldNotBeFiring() {
        var result = AlertResult.ok("test", "all good");
        assertFalse(result.firing());
        assertEquals("test", result.name());
        assertEquals("all good", result.summary());
    }

    @Test
    void alertResultFiringShouldBeFiring() {
        var result = AlertResult.firing("test", "something broke");
        assertTrue(result.firing());
    }

    @Test
    void alertStateFiringShouldReportFiring() {
        var state = AlertState.firing(Instant.now());
        assertTrue(state.isFiring());
        assertEquals(AlertState.FIRING, state.status());
        assertNull(state.lastNotified());
    }

    @Test
    void alertStateOkShouldNotBeFiring() {
        var state = AlertState.ok(Instant.now());
        assertFalse(state.isFiring());
        assertEquals(AlertState.OK, state.status());
    }

    @Test
    void alertStateWithNotifiedShouldPreserveFields() {
        var since = Instant.parse("2026-04-07T10:00:00Z");
        var notified = Instant.parse("2026-04-07T10:05:00Z");
        var state = AlertState.firing(since).withNotified(notified);

        assertTrue(state.isFiring());
        assertEquals(since, state.since());
        assertEquals(notified, state.lastNotified());
    }
}
