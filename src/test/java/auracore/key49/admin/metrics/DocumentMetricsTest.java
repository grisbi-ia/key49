package auracore.key49.admin.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DocumentMetricsTest {

    SimpleMeterRegistry registry;
    DocumentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new DocumentMetrics(registry);
    }

    // ── Global counters ──
    @Nested
    class GlobalCounters {

        @Test
        void shouldRegisterGlobalProcessedCounter() {
            var counter = registry.find("key49.documents.processed").counter();
            assertNotNull(counter);
            assertEquals(0.0, counter.count());
        }

        @Test
        void shouldRegisterGlobalRejectedCounter() {
            var counter = registry.find("key49.documents.rejected").counter();
            assertNotNull(counter);
            assertEquals(0.0, counter.count());
        }

        @Test
        void shouldRegisterGlobalFailedCounter() {
            var counter = registry.find("key49.documents.failed.global").counter();
            assertNotNull(counter);
            assertEquals(0.0, counter.count());
        }

        @Test
        void shouldIncrementProcessed() {
            metrics.incrementProcessed();
            metrics.incrementProcessed();
            assertEquals(2.0, registry.find("key49.documents.processed").counter().count());
        }

        @Test
        void shouldIncrementRejected() {
            metrics.incrementRejected();
            assertEquals(1.0, registry.find("key49.documents.rejected").counter().count());
        }

        @Test
        void shouldIncrementFailed() {
            metrics.incrementFailed();
            assertEquals(1.0, registry.find("key49.documents.failed.global").counter().count());
        }
    }

    // ── Tenant-dimensioned counters ──
    @Nested
    class TenantCounters {

        @Test
        void shouldRecordCreatedWithTenantAndType() {
            metrics.recordCreated("tenant_acme", "01");
            metrics.recordCreated("tenant_acme", "01");
            metrics.recordCreated("tenant_beta", "04");

            var acmeInvoice = registry.find("key49.documents.created")
                    .tag("tenant", "tenant_acme").tag("type", "01").counter();
            assertNotNull(acmeInvoice);
            assertEquals(2.0, acmeInvoice.count());

            var betaCreditNote = registry.find("key49.documents.created")
                    .tag("tenant", "tenant_beta").tag("type", "04").counter();
            assertNotNull(betaCreditNote);
            assertEquals(1.0, betaCreditNote.count());
        }

        @Test
        void shouldRecordAuthorizedAndIncrementGlobal() {
            metrics.recordAuthorized("tenant_acme");
            metrics.recordAuthorized("tenant_acme");
            metrics.recordAuthorized("tenant_beta");

            var acme = registry.find("key49.documents.authorized")
                    .tag("tenant", "tenant_acme").counter();
            assertNotNull(acme);
            assertEquals(2.0, acme.count());

            var beta = registry.find("key49.documents.authorized")
                    .tag("tenant", "tenant_beta").counter();
            assertNotNull(beta);
            assertEquals(1.0, beta.count());

            // Global processed counter should also increment
            assertEquals(3.0, registry.find("key49.documents.processed").counter().count());
        }

        @Test
        void shouldRecordRejectedWithReasonAndIncrementGlobal() {
            metrics.recordRejected("tenant_acme", "35");
            metrics.recordRejected("tenant_acme", "45");
            metrics.recordRejected("tenant_beta", "52");

            var acme35 = registry.find("key49.documents.failed")
                    .tag("tenant", "tenant_acme").tag("reason", "35").counter();
            assertNotNull(acme35);
            assertEquals(1.0, acme35.count());

            var acme45 = registry.find("key49.documents.failed")
                    .tag("tenant", "tenant_acme").tag("reason", "45").counter();
            assertNotNull(acme45);
            assertEquals(1.0, acme45.count());

            // Global rejected counter
            assertEquals(3.0, registry.find("key49.documents.rejected").counter().count());
        }

        @Test
        void shouldRecordRejectedWithNullReasonAsUnknown() {
            metrics.recordRejected("tenant_acme", null);

            var counter = registry.find("key49.documents.failed")
                    .tag("tenant", "tenant_acme").tag("reason", "UNKNOWN").counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        void shouldRecordFailedAndIncrementGlobal() {
            metrics.recordFailed("tenant_acme");

            var counter = registry.find("key49.documents.failed")
                    .tag("tenant", "tenant_acme").tag("reason", "RETRIES_EXHAUSTED").counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());

            // Global failed counter
            assertEquals(1.0, registry.find("key49.documents.failed").counter().count());
        }
    }

    // ── SRI timers ──
    @Nested
    class SriTimers {

        @Test
        void shouldReturnReceptionTimerWithTenantTag() {
            Timer timer = metrics.sriReceptionTimer("tenant_acme");
            assertNotNull(timer);
            assertEquals("key49.sri.latency", timer.getId().getName());
            assertEquals("tenant_acme", timer.getId().getTag("tenant"));
            assertEquals("reception", timer.getId().getTag("operation"));
        }

        @Test
        void shouldReturnAuthorizationTimerWithTenantTag() {
            Timer timer = metrics.sriAuthorizationTimer("tenant_acme");
            assertNotNull(timer);
            assertEquals("key49.sri.latency", timer.getId().getName());
            assertEquals("tenant_acme", timer.getId().getTag("tenant"));
            assertEquals("authorization", timer.getId().getTag("operation"));
        }

        @Test
        void shouldReturnSameTimerForSameTenant() {
            Timer t1 = metrics.sriReceptionTimer("tenant_acme");
            Timer t2 = metrics.sriReceptionTimer("tenant_acme");
            assertEquals(t1.getId(), t2.getId());
        }

        @Test
        void shouldReturnDifferentTimersForDifferentTenants() {
            Timer t1 = metrics.sriReceptionTimer("tenant_acme");
            Timer t2 = metrics.sriReceptionTimer("tenant_beta");
            assertNotNull(t1);
            assertNotNull(t2);
            assertEquals("tenant_acme", t1.getId().getTag("tenant"));
            assertEquals("tenant_beta", t2.getId().getTag("tenant"));
        }
    }

    // ── Notification counters ──
    @Nested
    class NotificationCounters {

        @Test
        void shouldRecordEmailSent() {
            metrics.recordEmailSent("tenant_acme");
            metrics.recordEmailSent("tenant_acme");

            var counter = registry.find("key49.email.sent")
                    .tag("tenant", "tenant_acme").counter();
            assertNotNull(counter);
            assertEquals(2.0, counter.count());
        }

        @Test
        void shouldRecordEmailFailed() {
            metrics.recordEmailFailed("tenant_acme");

            var counter = registry.find("key49.email.failed")
                    .tag("tenant", "tenant_acme").counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        void shouldRecordWebhookDispatched() {
            metrics.recordWebhookDispatched("tenant_acme");

            var counter = registry.find("key49.webhook.dispatched")
                    .tag("tenant", "tenant_acme").counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        void shouldIsolateTenantNotificationMetrics() {
            metrics.recordEmailSent("tenant_acme");
            metrics.recordEmailSent("tenant_beta");
            metrics.recordEmailSent("tenant_beta");

            assertEquals(1.0, registry.find("key49.email.sent")
                    .tag("tenant", "tenant_acme").counter().count());
            assertEquals(2.0, registry.find("key49.email.sent")
                    .tag("tenant", "tenant_beta").counter().count());
        }
    }

    // ── Deprecated timers ──
    @SuppressWarnings("deprecation")
    @Nested
    class DeprecatedTimers {

        @Test
        void shouldReturnLegacyReceptionTimer() {
            Timer timer = metrics.sriReceptionTimer();
            assertNotNull(timer);
            assertEquals("key49.sri.request.duration", timer.getId().getName());
            assertEquals("reception", timer.getId().getTag("operation"));
        }

        @Test
        void shouldReturnLegacyAuthorizationTimer() {
            Timer timer = metrics.sriAuthorizationTimer();
            assertNotNull(timer);
            assertEquals("key49.sri.request.duration", timer.getId().getName());
            assertEquals("authorization", timer.getId().getTag("operation"));
        }
    }

    // ── Tenant isolation ──
    @Nested
    class TenantIsolation {

        @Test
        void shouldKeepTenantCountersIndependent() {
            metrics.recordCreated("tenant_a", "01");
            metrics.recordCreated("tenant_b", "01");
            metrics.recordCreated("tenant_b", "01");

            assertEquals(1.0, registry.find("key49.documents.created")
                    .tag("tenant", "tenant_a").tag("type", "01").counter().count());
            assertEquals(2.0, registry.find("key49.documents.created")
                    .tag("tenant", "tenant_b").tag("type", "01").counter().count());
        }

        @Test
        void shouldTrackMultipleDocumentTypesPerTenant() {
            metrics.recordCreated("tenant_a", "01"); // Invoice
            metrics.recordCreated("tenant_a", "04"); // Credit note
            metrics.recordCreated("tenant_a", "01"); // Another invoice

            assertEquals(2.0, registry.find("key49.documents.created")
                    .tag("tenant", "tenant_a").tag("type", "01").counter().count());
            assertEquals(1.0, registry.find("key49.documents.created")
                    .tag("tenant", "tenant_a").tag("type", "04").counter().count());
        }
    }
}
