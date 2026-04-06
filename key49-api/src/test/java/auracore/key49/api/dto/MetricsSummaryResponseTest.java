package auracore.key49.api.dto;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import auracore.key49.api.dto.MetricsSummaryResponse.PeriodSnapshot;

/**
 * Tests unitarios para MetricsSummaryResponse y PeriodSnapshot.
 */
class MetricsSummaryResponseTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(new JavaTimeModule());

    @Nested
    @DisplayName("PeriodSnapshot")
    class PeriodSnapshotTests {

        @Test
        @DisplayName("preserva todos los campos")
        void preservesFields() {
            var snapshot = new PeriodSnapshot(100, 80, 5, 10, 5);
            assertEquals(100, snapshot.total());
            assertEquals(80, snapshot.authorized());
            assertEquals(5, snapshot.rejected());
            assertEquals(10, snapshot.pending());
            assertEquals(5, snapshot.failed());
        }

        @Test
        @DisplayName("acepta valores en cero")
        void acceptsZeros() {
            var snapshot = new PeriodSnapshot(0, 0, 0, 0, 0);
            assertEquals(0, snapshot.total());
            assertEquals(0, snapshot.authorized());
        }
    }

    @Nested
    @DisplayName("MetricsSummaryResponse")
    class ResponseTests {

        @Test
        @DisplayName("preserva todos los campos")
        void preservesAllFields() {
            var today = new PeriodSnapshot(10, 8, 1, 0, 1);
            var month = new PeriodSnapshot(250, 220, 10, 5, 15);
            var lastInvoice = Instant.parse("2026-04-06T12:00:00Z");

            var response = new MetricsSummaryResponse(today, month, 90, lastInvoice);

            assertEquals(today, response.today());
            assertEquals(month, response.month());
            assertEquals(90, response.certificateExpiresInDays());
            assertEquals(lastInvoice, response.lastInvoiceAt());
        }

        @Test
        @DisplayName("lastInvoiceAt puede ser null")
        void lastInvoiceAtCanBeNull() {
            var snapshot = new PeriodSnapshot(0, 0, 0, 0, 0);
            var response = new MetricsSummaryResponse(snapshot, snapshot, -1, null);
            assertNull(response.lastInvoiceAt());
            assertEquals(-1, response.certificateExpiresInDays());
        }
    }

    @Nested
    @DisplayName("Serialización JSON")
    class Serialization {

        @Test
        @DisplayName("serializa con snake_case")
        void serializesWithSnakeCase() throws Exception {
            var today = new PeriodSnapshot(5, 3, 1, 1, 0);
            var month = new PeriodSnapshot(50, 40, 5, 3, 2);
            var response = new MetricsSummaryResponse(today, month, 120, Instant.parse("2026-04-06T10:00:00Z"));

            var json = mapper.writeValueAsString(response);

            assertTrue(json.contains("\"certificate_expires_in_days\":120"));
            assertTrue(json.contains("\"last_invoice_at\""));
        }

        @Test
        @DisplayName("omite lastInvoiceAt null gracias a NON_NULL")
        void omitsNullLastInvoiceAt() throws Exception {
            var snapshot = new PeriodSnapshot(0, 0, 0, 0, 0);
            var response = new MetricsSummaryResponse(snapshot, snapshot, 30, null);

            var json = mapper.writeValueAsString(response);

            assertFalse(json.contains("\"last_invoice_at\""));
            assertTrue(json.contains("\"certificate_expires_in_days\":30"));
        }

        @Test
        @DisplayName("serializa PeriodSnapshot con todos los campos")
        void serializesSnapshot() throws Exception {
            var snapshot = new PeriodSnapshot(100, 80, 5, 10, 5);
            var json = mapper.writeValueAsString(snapshot);

            assertTrue(json.contains("\"total\":100"));
            assertTrue(json.contains("\"authorized\":80"));
            assertTrue(json.contains("\"rejected\":5"));
            assertTrue(json.contains("\"pending\":10"));
            assertTrue(json.contains("\"failed\":5"));
        }
    }
}
