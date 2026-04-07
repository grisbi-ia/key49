package auracore.key49.ride.generator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests para DebitNoteRideGenerator.
 */

class DebitNoteRideGeneratorTest {

    private static final String ACCESS_KEY = "2506202505099271531200110010020000000011234567813";

    @Test
    void generateProducesValidPdf() {
        var data = buildSampleData(true);

        byte[] pdf = DebitNoteRideGenerator.generate(data);

        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithoutAuthorizationAddsWatermark() {
        var data = buildSampleData(false);

        byte[] pdf = DebitNoteRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
        assertTrue(pdf.length > 100);
    }

    @Test
    void authorizedDiffersFromUnauthorized() {
        byte[] authPdf = DebitNoteRideGenerator.generate(buildSampleData(true));
        byte[] unauthPdf = DebitNoteRideGenerator.generate(buildSampleData(false));

        assertNotNull(authPdf);
        assertNotNull(unauthPdf);
        assertPdfMagicBytes(authPdf);
        assertPdfMagicBytes(unauthPdf);
        assertTrue(authPdf.length != unauthPdf.length,
                "Authorized and unauthorized PDFs should differ in size due to watermark");
    }

    @Test
    void generateWithMultipleReasonsProducesPdf() {
        var reasons = List.of(
                new DebitNoteRideData.Reason("Intereses por mora", new BigDecimal("50.00")),
                new DebitNoteRideData.Reason("Gastos administrativos", new BigDecimal("25.00")),
                new DebitNoteRideData.Reason("Comisión bancaria", new BigDecimal("10.00"))
        );

        var data = buildDataWithReasons(reasons);
        byte[] pdf = DebitNoteRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithPaymentsProducesPdf() {
        var data = buildSampleData(true);
        byte[] pdf = DebitNoteRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithoutAdditionalInfoAndPaymentsProducesPdf() {
        var data = new DebitNoteRideData(
                createIssuer(), ACCESS_KEY, ACCESS_KEY,
                LocalDateTime.now(), "1", "1",
                "001", "001", "000000001", LocalDate.now(),
                new RideData.Recipient("04", "1790567890001", "CLIENTE", null),
                "01", "001-001-000000100", LocalDate.now().minusDays(5),
                List.of(new DebitNoteRideData.Reason("Ajuste", new BigDecimal("10.00"))),
                List.of(new RideData.TotalTax("2", "0", new BigDecimal("10.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO)),
                new BigDecimal("10.00"), new BigDecimal("10.00"),
                null, null, true, null
        );

        byte[] pdf = DebitNoteRideGenerator.generate(data);
        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    // ── Helpers ──

    private DebitNoteRideData buildSampleData(boolean authorized) {
        return new DebitNoteRideData(
                createIssuer(),
                ACCESS_KEY,
                authorized ? ACCESS_KEY : null,
                authorized ? LocalDateTime.now() : null,
                "1",
                "1",
                "001",
                "001",
                "000000001",
                LocalDate.now(),
                new RideData.Recipient("04", "1790567890001", "CLIENTE PRUEBA CIA. LTDA.", "Guayaquil"),
                "01",
                "001-001-000000100",
                LocalDate.now().minusDays(5),
                List.of(new DebitNoteRideData.Reason("Intereses por mora en pago", new BigDecimal("50.00"))),
                List.of(new RideData.TotalTax("2", "4", new BigDecimal("50.00"),
                        new BigDecimal("15.00"), new BigDecimal("7.50"))),
                new BigDecimal("50.00"),
                new BigDecimal("57.50"),
                List.of(new RideData.Payment("01", new BigDecimal("57.50"), 30, "dias")),
                new LinkedHashMap<>() {{
                    put("Dirección", "Guayaquil, Av. 9 de Octubre");
                    put("Email", "cliente@test.com");
                }},
                authorized,
                null
        );
    }

    private DebitNoteRideData buildDataWithReasons(List<DebitNoteRideData.Reason> reasons) {
        return new DebitNoteRideData(
                createIssuer(), ACCESS_KEY, ACCESS_KEY,
                LocalDateTime.now(), "1", "1",
                "001", "001", "000000001", LocalDate.now(),
                new RideData.Recipient("04", "1790567890001", "CLIENTE", null),
                "01", "001-001-000000100", LocalDate.now().minusDays(5),
                reasons,
                List.of(new RideData.TotalTax("2", "4", new BigDecimal("85.00"),
                        new BigDecimal("15.00"), new BigDecimal("12.75"))),
                new BigDecimal("85.00"), new BigDecimal("97.75"),
                null, null, true, null
        );
    }

    private RideData.Issuer createIssuer() {
        return new RideData.Issuer(
                "1790012345001", "EMPRESA DEMO S.A.", "DEMO",
                "Quito, Av. Principal 123", "Sucursal Norte",
                true, null, null, null
        );
    }

    private void assertPdfMagicBytes(byte[] pdf) {
        assertTrue(pdf.length >= 4);
        assertTrue(pdf[0] == '%' && pdf[1] == 'P' && pdf[2] == 'D' && pdf[3] == 'F',
                "PDF should start with %PDF magic bytes");
    }
}
