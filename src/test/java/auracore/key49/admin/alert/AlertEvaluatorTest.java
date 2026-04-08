package auracore.key49.admin.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests unitarios para AlertEvaluator — verifica transiciones de estado
 * (OK→FIRING, FIRING→OK) y lógica de reminders.
 *
 * <p>Usa implementaciones stub de AlertStateRepository y AlertNotifier
 * para aislar la lógica del evaluador.</p>
 */
class AlertEvaluatorTest {

    private AlertEvaluator evaluator;
    private StubStateRepository stateRepo;
    private StubNotifier notifier;

    @BeforeEach
    void setUp() {
        evaluator = new AlertEvaluator();
        stateRepo = new StubStateRepository();
        notifier = new StubNotifier();

        evaluator.stateRepository = stateRepo;
        evaluator.notifier = notifier;
        evaluator.alertsEnabled = true;
        evaluator.reminderInterval = Duration.ofHours(4);
    }

    @Test
    void firstFiringShouldNotifyAndSaveState() {
        AlertRule rule = () -> AlertResult.firing("test_alert", "something is wrong");
        evaluator.evaluateRule(rule);

        assertEquals(1, notifier.firingCount);
        assertEquals(0, notifier.resolvedCount);
        assertEquals(0, notifier.reminderCount);

        var saved = stateRepo.states.get("test_alert");
        assertNotNull(saved);
        assertTrue(saved.isFiring());
        assertNotNull(saved.lastNotified());
    }

    @Test
    void okWhenNoStateShouldNotNotify() {
        AlertRule rule = () -> AlertResult.ok("test_alert", "everything fine");
        evaluator.evaluateRule(rule);

        assertEquals(0, notifier.firingCount);
        assertEquals(0, notifier.resolvedCount);
        assertEquals(0, notifier.reminderCount);
    }

    @Test
    void firingToOkShouldNotifyResolved() {
        // Set up a previously FIRING state
        stateRepo.states.put("test_alert", AlertState.firing(Instant.now().minusSeconds(300)));

        AlertRule rule = () -> AlertResult.ok("test_alert", "recovered");
        evaluator.evaluateRule(rule);

        assertEquals(0, notifier.firingCount);
        assertEquals(1, notifier.resolvedCount);

        var saved = stateRepo.states.get("test_alert");
        assertNotNull(saved);
        assertFalse(saved.isFiring());
    }

    @Test
    void firingWithRecentNotificationShouldNotSendReminder() {
        // Last notification was 1 hour ago (reminder interval is 4h)
        var state = AlertState.firing(Instant.now().minusSeconds(3600))
                .withNotified(Instant.now().minusSeconds(3600));
        stateRepo.states.put("test_alert", state);

        AlertRule rule = () -> AlertResult.firing("test_alert", "still broken");
        evaluator.evaluateRule(rule);

        assertEquals(0, notifier.firingCount);
        assertEquals(0, notifier.resolvedCount);
        assertEquals(0, notifier.reminderCount);
    }

    @Test
    void firingWithOldNotificationShouldSendReminder() {
        // Last notification was 5 hours ago (reminder interval is 4h)
        var fiveHoursAgo = Instant.now().minusSeconds(5 * 3600);
        var state = AlertState.firing(fiveHoursAgo).withNotified(fiveHoursAgo);
        stateRepo.states.put("test_alert", state);

        AlertRule rule = () -> AlertResult.firing("test_alert", "still broken");
        evaluator.evaluateRule(rule);

        assertEquals(0, notifier.firingCount);
        assertEquals(0, notifier.resolvedCount);
        assertEquals(1, notifier.reminderCount);

        // State should be updated with new lastNotified
        var saved = stateRepo.states.get("test_alert");
        assertTrue(saved.isFiring());
        assertTrue(saved.lastNotified().isAfter(fiveHoursAgo));
    }

    @Test
    void disabledAlertsShouldSkipEvaluation() {
        evaluator.alertsEnabled = false;

        // These should do nothing because alerts are disabled
        evaluator.evaluateInfraRules();
        evaluator.evaluateCertRule();

        assertEquals(0, notifier.firingCount);
        assertEquals(0, notifier.resolvedCount);
    }

    // --- Stubs ---

    static class StubStateRepository extends AlertStateRepository {
        final Map<String, AlertState> states = new HashMap<>();

        StubStateRepository() {
            // Don't inject Redis
        }

        @Override
        public AlertState get(String alertName) {
            return states.get(alertName);
        }

        @Override
        public void save(String alertName, AlertState state) {
            states.put(alertName, state);
        }
    }

    static class StubNotifier extends AlertNotifier {
        int firingCount;
        int resolvedCount;
        int reminderCount;

        StubNotifier() {
            // Don't inject mailer
        }

        @Override
        public void notifyFiring(AlertResult result) {
            firingCount++;
        }

        @Override
        public void notifyResolved(AlertResult result) {
            resolvedCount++;
        }

        @Override
        public void notifyReminder(AlertResult result, Duration activeSince) {
            reminderCount++;
        }
    }
}
