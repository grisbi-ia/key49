package auracore.key49.api.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Test unitario para la lógica de métricas del portal.
 */
class PortalMetricsTest {

    @Nested
    class DailyCountTests {

        @Test
        void shouldCreateDailyCount() {
            var dc = new PortalResource.DailyCount(LocalDate.of(2025, 6, 15), 42, 85);
            assertEquals(LocalDate.of(2025, 6, 15), dc.date());
            assertEquals(42, dc.count());
            assertEquals(85, dc.pct());
        }

        @Test
        void shouldHandleZeroCount() {
            var dc = new PortalResource.DailyCount(LocalDate.of(2025, 1, 1), 0, 0);
            assertEquals(0, dc.count());
            assertEquals(0, dc.pct());
        }

        @Test
        void shouldHandleMaxPercentage() {
            var dc = new PortalResource.DailyCount(LocalDate.of(2025, 6, 15), 100, 100);
            assertEquals(100, dc.pct());
        }
    }

    @Nested
    class CertificateStatusLogicTests {

        @Test
        void shouldReturnNoneWhenNoCertificate() {
            String certStatus = computeCertStatus(null);
            assertEquals("none", certStatus);
        }

        @Test
        void shouldReturnExpiredWhenPastDate() {
            var expired = Instant.now().minus(Duration.ofDays(10));
            assertEquals("expired", computeCertStatus(expired));
        }

        @Test
        void shouldReturnExpiringWithin30Days() {
            var expiring = Instant.now().plus(Duration.ofDays(15));
            assertEquals("expiring", computeCertStatus(expiring));
        }

        @Test
        void shouldReturnExpiringAtExactly30Days() {
            // 30 days = boundary → "expiring" (≤30)
            var boundary = Instant.now().plus(Duration.ofDays(30));
            assertEquals("expiring", computeCertStatus(boundary));
        }

        @Test
        void shouldReturnValidWhenMoreThan30Days() {
            var valid = Instant.now().plus(Duration.ofDays(90));
            assertEquals("valid", computeCertStatus(valid));
        }

        @Test
        void shouldReturnExpiredWhenOverOneDayPast() {
            var overOneDay = Instant.now().minus(Duration.ofDays(2));
            assertEquals("expired", computeCertStatus(overOneDay));
        }

        @Test
        void shouldReturnExpiringWhenJustExpiredLessThanOneDay() {
            // Duration.toDays() truncates toward zero, so < 24h ago = 0 days = "expiring"
            var justExpired = Instant.now().minus(Duration.ofHours(1));
            assertEquals("expiring", computeCertStatus(justExpired));
        }

        /**
         * Replica la lógica de certificado de PortalResource.metricsPage().
         */
        private String computeCertStatus(Instant certificateExpiration) {
            if (certificateExpiration == null) {
                return "none";
            }
            long certDaysLeft = Duration.between(Instant.now(), certificateExpiration).toDays();
            if (certDaysLeft < 0) {
                return "expired";
            } else if (certDaysLeft <= 30) {
                return "expiring";
            } else {
                return "valid";
            }
        }
    }
}
