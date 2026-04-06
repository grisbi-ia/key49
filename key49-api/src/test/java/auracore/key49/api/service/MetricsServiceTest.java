package auracore.key49.api.service;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import auracore.key49.api.dto.MetricsSummaryResponse.PeriodSnapshot;
import auracore.key49.core.model.enums.DocumentStatus;

/**
 * Tests unitarios para la lógica de cálculo de snapshots en MetricsService.
 * Invoca el método privado toSnapshot via reflection para verificar la
 * clasificación de estados.
 */
class MetricsServiceTest {

    private final MetricsService service = new MetricsService();

    private PeriodSnapshot toSnapshot(Map<DocumentStatus, Long> counts) {
        try {
            var method = MetricsService.class.getDeclaredMethod("toSnapshot", Map.class);
            method.setAccessible(true);
            return (PeriodSnapshot) method.invoke(service, counts);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("toSnapshot — clasificación de estados")
    class ToSnapshot {

        @Test
        @DisplayName("mapa vacío retorna todo en cero")
        void emptyMap() {
            var result = toSnapshot(Map.of());
            assertEquals(0, result.total());
            assertEquals(0, result.authorized());
            assertEquals(0, result.rejected());
            assertEquals(0, result.pending());
            assertEquals(0, result.failed());
        }

        @Test
        @DisplayName("AUTHORIZED, NOTIFIED y VOIDED se suman como authorized")
        void authorizedGroup() {
            var counts = Map.<DocumentStatus, Long>of(
                    DocumentStatus.AUTHORIZED, 10L,
                    DocumentStatus.NOTIFIED, 5L,
                    DocumentStatus.VOIDED, 2L);

            var result = toSnapshot(counts);
            assertEquals(17, result.total());
            assertEquals(17, result.authorized()); // 10 + 5 + 2
            assertEquals(0, result.rejected());
            assertEquals(0, result.pending());
            assertEquals(0, result.failed());
        }

        @Test
        @DisplayName("REJECTED cuenta como rejected")
        void rejectedCount() {
            var counts = Map.<DocumentStatus, Long>of(DocumentStatus.REJECTED, 3L);

            var result = toSnapshot(counts);
            assertEquals(3, result.total());
            assertEquals(0, result.authorized());
            assertEquals(3, result.rejected());
            assertEquals(0, result.pending());
            assertEquals(0, result.failed());
        }

        @Test
        @DisplayName("FAILED cuenta como failed")
        void failedCount() {
            var counts = Map.<DocumentStatus, Long>of(DocumentStatus.FAILED, 7L);

            var result = toSnapshot(counts);
            assertEquals(7, result.total());
            assertEquals(0, result.authorized());
            assertEquals(0, result.rejected());
            assertEquals(0, result.pending());
            assertEquals(7, result.failed());
        }

        @Test
        @DisplayName("CREATED, SIGNED, SENT, RECEIVED, RETRY cuentan como pending")
        void pendingStates() {
            var counts = Map.<DocumentStatus, Long>of(
                    DocumentStatus.CREATED, 2L,
                    DocumentStatus.SIGNED, 3L,
                    DocumentStatus.SENT, 1L,
                    DocumentStatus.RECEIVED, 4L,
                    DocumentStatus.RETRY, 1L);

            var result = toSnapshot(counts);
            assertEquals(11, result.total());
            assertEquals(0, result.authorized());
            assertEquals(0, result.rejected());
            assertEquals(11, result.pending()); // 2+3+1+4+1
            assertEquals(0, result.failed());
        }

        @Test
        @DisplayName("mezcla de todos los estados")
        void mixedStates() {
            // Map.of only supports up to 10 entries
            var counts = Map.<DocumentStatus, Long>of(
                    DocumentStatus.AUTHORIZED, 50L,
                    DocumentStatus.NOTIFIED, 30L,
                    DocumentStatus.VOIDED, 5L,
                    DocumentStatus.REJECTED, 10L,
                    DocumentStatus.FAILED, 3L,
                    DocumentStatus.CREATED, 2L,
                    DocumentStatus.SIGNED, 1L,
                    DocumentStatus.RETRY, 1L);

            var result = toSnapshot(counts);
            assertEquals(102, result.total());
            assertEquals(85, result.authorized()); // 50+30+5
            assertEquals(10, result.rejected());
            assertEquals(4, result.pending()); // 2+1+1
            assertEquals(3, result.failed());
        }
    }
}
