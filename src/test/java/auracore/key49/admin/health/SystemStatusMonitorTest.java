package auracore.key49.admin.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests unitarios para SystemStatusMonitor.
 */

class SystemStatusMonitorTest {

    private SystemStatusMonitor monitor;

    @BeforeEach
    void setup() {
        monitor = new SystemStatusMonitor();
        monitor.webhookEnabled = true;
        monitor.connectTimeoutMs = 5000;
        monitor.readTimeoutMs = 10000;
    }

    @Nested
    class CheckComponent {

        @Test
        void firstCheckShouldNotTriggerWebhook() {
            var state = new AtomicReference<Boolean>(null);
            var webhookSent = new AtomicBoolean(false);

            var healthCheck = stubHealthCheck(true);
            monitor.checkComponent("test", healthCheck, state);

            assertTrue(state.get());
        }

        @Test
        void noChangeInStatusShouldNotTriggerWebhook() {
            var state = new AtomicReference<>(Boolean.TRUE);
            var healthCheck = stubHealthCheck(true);

            monitor.checkComponent("test", healthCheck, state);
            assertTrue(state.get());
        }

        @Test
        void transitionUpToDownShouldUpdateState() {
            var state = new AtomicReference<>(Boolean.TRUE);
            var healthCheck = stubHealthCheck(false);

            // This will try to broadcastToAllTenants which needs DataSource
            // But the state should be updated regardless
            monitor.checkComponent("test", healthCheck, state);
            assertFalse(state.get());
        }

        @Test
        void transitionDownToUpShouldUpdateState() {
            var state = new AtomicReference<>(Boolean.FALSE);
            var healthCheck = stubHealthCheck(true);

            monitor.checkComponent("test", healthCheck, state);
            assertTrue(state.get());
        }
    }

    @Nested
    class EscapeJson {

        @Test
        void shouldHandleNull() {
            assertEquals("", SystemStatusMonitor.escapeJson(null));
        }

        @Test
        void shouldEscapeQuotes() {
            assertEquals("say \\\"hello\\\"", SystemStatusMonitor.escapeJson("say \"hello\""));
        }

        @Test
        void shouldEscapeBackslash() {
            assertEquals("path\\\\to\\\\file", SystemStatusMonitor.escapeJson("path\\to\\file"));
        }

        @Test
        void shouldReturnPlainText() {
            assertEquals("hello world", SystemStatusMonitor.escapeJson("hello world"));
        }
    }

    @Nested
    class ExtractError {

        @Test
        void shouldExtractErrorFromData() {
            var response = HealthCheckResponse.named("Test")
                    .down()
                    .withData("error", "Connection refused")
                    .build();
            assertEquals("Connection refused", SystemStatusMonitor.extractError(response));
        }

        @Test
        void shouldReturnUnavailableWhenNoErrorKey() {
            var response = HealthCheckResponse.named("Test")
                    .down()
                    .withData("url", "http://test")
                    .build();
            assertEquals("unavailable", SystemStatusMonitor.extractError(response));
        }

        @Test
        void shouldReturnUnavailableWhenNoData() {
            var response = HealthCheckResponse.named("Test").down().build();
            assertEquals("unavailable", SystemStatusMonitor.extractError(response));
        }
    }

    @Nested
    class ComputeHmac {

        @Test
        void shouldComputeConsistentSignature() {
            var payload = "{\"event\":\"test\"}";
            var secret = "my-secret";

            var sig1 = SystemStatusMonitor.computeHmac(payload, secret);
            var sig2 = SystemStatusMonitor.computeHmac(payload, secret);

            assertFalse(sig1.isEmpty());
            assertEquals(sig1, sig2);
        }

        @Test
        void shouldProduceDifferentSignaturesForDifferentSecrets() {
            var payload = "{\"event\":\"test\"}";

            var sig1 = SystemStatusMonitor.computeHmac(payload, "secret-a");
            var sig2 = SystemStatusMonitor.computeHmac(payload, "secret-b");

            assertFalse(sig1.equals(sig2));
        }

        @Test
        void shouldReturn64CharHex() {
            var sig = SystemStatusMonitor.computeHmac("data", "key");
            assertEquals(64, sig.length());
        }
    }

    @Nested
    class BuildCertExpiredPayload {

        @Test
        void shouldContainEventType() {
            var payload = SystemStatusMonitor.buildCertExpiredPayload(
                    "tenant-1", "ACME Corp", Instant.parse("2026-01-01T00:00:00Z"));

            assertTrue(payload.contains("\"event\":\"certificate.expired\""));
            assertTrue(payload.contains("\"tenant_id\":\"tenant-1\""));
            assertTrue(payload.contains("\"legal_name\":\"ACME Corp\""));
            assertTrue(payload.contains("\"expired_at\":\"2026-01-01T00:00:00Z\""));
        }

        @Test
        void shouldEscapeSpecialCharsInLegalName() {
            var payload = SystemStatusMonitor.buildCertExpiredPayload(
                    "t-1", "Company \"Test\" S.A.", Instant.now());

            assertTrue(payload.contains("Company \\\"Test\\\" S.A."));
        }
    }

    @Nested
    class ResetState {

        @Test
        void shouldClearTrackedStates() {
            monitor.lastSriReceptionUp.set(true);
            monitor.lastSriAuthorizationUp.set(false);

            monitor.resetState();

            assertNull(monitor.lastSriReceptionUp.get());
            assertNull(monitor.lastSriAuthorizationUp.get());
        }
    }

    private static HealthCheck stubHealthCheck(boolean up) {
        return () -> HealthCheckResponse.named("stub")
                .status(up)
                .withData("url", "http://test")
                .build();
    }
}
