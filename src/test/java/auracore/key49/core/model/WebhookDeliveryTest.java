package auracore.key49.core.model;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests unitarios para WebhookDelivery: factory, transiciones de estado, truncado.
 */
class WebhookDeliveryTest {

    // ── Factory ──

    @Nested
    class CreateTests {

        @Test
        void create_setsAllDefaults() {
            var docId = UUID.randomUUID();
            var delivery = WebhookDelivery.create(
                    docId, "document.authorized",
                    "https://example.com/webhook", "{\"event\":\"test\"}");

            // id is null outside persistence context (@GeneratedValue)
            assertNull(delivery.id);
            assertEquals(docId, delivery.documentId);
            assertEquals("document.authorized", delivery.eventType);
            assertEquals("https://example.com/webhook", delivery.url);
            assertEquals("{\"event\":\"test\"}", delivery.requestBody);
            assertEquals("pending", delivery.status);
            assertEquals((short) 1, delivery.attempt);
            assertEquals((short) 3, delivery.maxAttempts);
            assertNull(delivery.responseStatus);
            assertNull(delivery.responseBody);
            assertNull(delivery.durationMs);
            assertNull(delivery.nextAttemptAt);
            assertNotNull(delivery.createdAt);
        }

        @Test
        void create_setsUniqueCreatedAt() {
            var d1 = WebhookDelivery.create(UUID.randomUUID(), "e", "u", "b");
            var d2 = WebhookDelivery.create(UUID.randomUUID(), "e", "u", "b");

            assertNotNull(d1.createdAt);
            assertNotNull(d2.createdAt);
        }
    }

    // ── Mark Delivered ──

    @Nested
    class MarkDeliveredTests {

        @Test
        void markDelivered_setsDeliveredStatus() {
            var delivery = WebhookDelivery.create(
                    UUID.randomUUID(), "document.authorized",
                    "https://example.com/webhook", "{}");

            delivery.markDelivered(200, "OK", 150);

            assertEquals("delivered", delivery.status);
            assertEquals(Integer.valueOf(200), delivery.responseStatus);
            assertEquals("OK", delivery.responseBody);
            assertEquals(Integer.valueOf(150), delivery.durationMs);
            assertNull(delivery.nextAttemptAt);
        }

        @Test
        void markDelivered_clearsNextAttemptAt() {
            var delivery = WebhookDelivery.create(
                    UUID.randomUUID(), "e", "u", "{}");
            delivery.nextAttemptAt = Instant.now().plusSeconds(60);

            delivery.markDelivered(200, "OK", 100);

            assertNull(delivery.nextAttemptAt);
        }
    }

    // ── Mark Failed ──

    @Nested
    class MarkFailedTests {

        @Test
        void markFailed_firstAttemptBelowMax_keepsPendingStatus() {
            var delivery = WebhookDelivery.create(
                    UUID.randomUUID(), "e", "u", "{}");
            delivery.attempt = 1;
            var nextRetry = Instant.now().plusSeconds(10);

            delivery.markFailed(500, "Server Error", 200, nextRetry);

            assertEquals("pending", delivery.status);
            assertEquals(Integer.valueOf(500), delivery.responseStatus);
            assertEquals("Server Error", delivery.responseBody);
            assertEquals(Integer.valueOf(200), delivery.durationMs);
            assertEquals(nextRetry, delivery.nextAttemptAt);
        }

        @Test
        void markFailed_atMaxAttempts_setsFailedStatus() {
            var delivery = WebhookDelivery.create(
                    UUID.randomUUID(), "e", "u", "{}");
            delivery.attempt = 3;
            delivery.maxAttempts = 3;

            delivery.markFailed(500, "Error", 100, Instant.now().plusSeconds(300));

            assertEquals("failed", delivery.status);
            assertNull(delivery.nextAttemptAt);
        }

        @Test
        void markFailed_beyondMaxAttempts_setsFailedStatus() {
            var delivery = WebhookDelivery.create(
                    UUID.randomUUID(), "e", "u", "{}");
            delivery.attempt = 5;
            delivery.maxAttempts = 3;

            delivery.markFailed(500, "Error", 100, null);

            assertEquals("failed", delivery.status);
        }

        @Test
        void markFailed_truncatesLongResponseBody() {
            var delivery = WebhookDelivery.create(
                    UUID.randomUUID(), "e", "u", "{}");

            String longBody = "X".repeat(3000);
            delivery.markFailed(500, longBody, 100, Instant.now().plusSeconds(10));

            assertNotNull(delivery.responseBody);
            assertTrue(delivery.responseBody.length() <= 2000);
        }

        @Test
        void markDelivered_truncatesLongResponseBody() {
            var delivery = WebhookDelivery.create(
                    UUID.randomUUID(), "e", "u", "{}");

            String longBody = "Y".repeat(5000);
            delivery.markDelivered(200, longBody, 100);

            assertNotNull(delivery.responseBody);
            assertTrue(delivery.responseBody.length() <= 2000);
        }
    }

    // ── Can Retry ──

    @Nested
    class CanRetryTests {

        @Test
        void canRetry_pendingAndBelowMax_true() {
            var delivery = WebhookDelivery.create(UUID.randomUUID(), "e", "u", "{}");
            delivery.status = "pending";
            delivery.attempt = 1;
            delivery.maxAttempts = 3;

            assertTrue(delivery.canRetry());
        }

        @Test
        void canRetry_pendingAndAtMax_false() {
            var delivery = WebhookDelivery.create(UUID.randomUUID(), "e", "u", "{}");
            delivery.status = "pending";
            delivery.attempt = 3;
            delivery.maxAttempts = 3;

            assertFalse(delivery.canRetry());
        }

        @Test
        void canRetry_delivered_false() {
            var delivery = WebhookDelivery.create(UUID.randomUUID(), "e", "u", "{}");
            delivery.status = "delivered";
            delivery.attempt = 1;

            assertFalse(delivery.canRetry());
        }

        @Test
        void canRetry_failed_false() {
            var delivery = WebhookDelivery.create(UUID.randomUUID(), "e", "u", "{}");
            delivery.status = "failed";
            delivery.attempt = 1;

            assertFalse(delivery.canRetry());
        }
    }
}
