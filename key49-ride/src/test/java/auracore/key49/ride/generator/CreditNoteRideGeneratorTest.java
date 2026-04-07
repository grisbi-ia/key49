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
 * Tests para CreditNoteRideGenerator.
 */

class CreditNoteRideGeneratorTest {

    private static final String ACCESS_KEY = "2506202504099271531200110010020000000011234567813";

    @Test
    void generateProducesValidPdf() {
        var data = buildSampleData(true);

        byte[] pdf = CreditNoteRideGenerator.generate(data);

        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithoutAuthorizationAddsWatermark() {
        var data = buildSampleData(false);

        byte[] pdf = CreditNoteRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
        assertTrue(pdf.length > 100);
    }

    @Test
    void authorizedDiffersFromUnauthorized() {
        byte[] authPdf = CreditNoteRideGenerator.generate(buildSampleData(true));
        byte[] unauthPdf = CreditNoteRideGenerator.generate(buildSampleData(false));

        assertNotNull(authPdf);
        assertNotNull(unauthPdf);
        assertPdfMagicBytes(authPdf);
        assertPdfMagicBytes(unauthPdf);
        assertTrue(authPdf.length != unauthPdf.length,
                "Authorized and unauthorized PDFs should differ in size due to watermark");
    }

    @Test
    void generateWithMultipleItemsProducesPdf() {
        var items = List.of(
                new CreditNoteRideData.Item("INT-001", "Producto A",
                        new BigDecimal("10.00"), new BigDecimal("5.00"),
                        new BigDecimal("0.50"), new BigDecimal("49.50"),
                        List.of(createTax("2", "4", "15.00", "49.50", "7.43"))),
                new CreditNoteRideData.Item("INT-002", "Producto B",
                        new BigDecimal("5.00"), new BigDecimal("20.00"),
                        BigDecimal.ZERO, new BigDecimal("100.00"),
                        List.of(createTax("2", "0", "0.00", "100.00", "0.00")))
        );

        var data = buildDataWithItems(items);
        byte[] pdf = CreditNoteRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithAdditionalInfoProducesPdf() {
        var data = buildSampleData(true);
        byte[] pdf = CreditNoteRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithoutAdditionalInfoProducesPdf() {
        var data = new CreditNoteRideData(
                createIssuer(), ACCESS_KEY, ACCESS_KEY,
                LocalDateTime.now(), "1", "1",
                "001", "001", "000000001", LocalDate.now(),
                new RideData.Recipient("04", "1790567890001", "CLIENTE", null),
                "01", "001-001-000000100", LocalDate.now().minusDays(5),
                "Devolución",
                List.of(createDefaultItem()),
                List.of(new RideData.TotalTax("2", "4", new BigDecimal("50.00"),
                        new BigDecimal("15.00"), new BigDecimal("7.50"))),
                new BigDecimal("50.00"), new BigDecimal("57.50"), "DOLAR",
                null, true, null
        );

        byte[] pdf = CreditNoteRideGenerator.generate(data);
        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    // ── Helpers ──

    private CreditNoteRideData buildSampleData(boolean authorized) {
        return new CreditNoteRideData(
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
                "Devolución de producto por defecto de fabricación",
                List.of(createDefaultItem()),
                List.of(new RideData.TotalTax("2", "4", new BigDecimal("50.00"),
                        new BigDecimal("15.00"), new BigDecimal("7.50"))),
                new BigDecimal("50.00"),
                new BigDecimal("57.50"),
                "DOLAR",
                new LinkedHashMap<>() {{
                    put("Dirección", "Guayaquil, Av. 9 de Octubre");
                    put("Email", "cliente@test.com");
                }},
                authorized,
                null
        );
    }

    private CreditNoteRideData buildDataWithItems(List<CreditNoteRideData.Item> items) {
        return new CreditNoteRideData(
                createIssuer(), ACCESS_KEY, ACCESS_KEY,
                LocalDateTime.now(), "1", "1",
                "001", "001", "000000001", LocalDate.now(),
                new RideData.Recipient("04", "1790567890001", "CLIENTE", null),
                "01", "001-001-000000100", LocalDate.now().minusDays(5),
                "Devolución",
                items,
                List.of(new RideData.TotalTax("2", "4", new BigDecimal("149.50"),
                        new BigDecimal("15.00"), new BigDecimal("7.43"))),
                new BigDecimal("149.50"), new BigDecimal("156.93"), "DOLAR",
                null, true, null
        );
    }

    private RideData.Issuer createIssuer() {
        return new RideData.Issuer(
                "1790012345001", "EMPRESA DEMO S.A.", "DEMO",
                "Quito, Av. Principal 123", "Sucursal Norte",
                true, null, null, null
        );
    }

    private CreditNoteRideData.Item createDefaultItem() {
        return new CreditNoteRideData.Item(
                "PROD-001", "Servicio de hosting mensual",
                BigDecimal.ONE, new BigDecimal("50.00"),
                BigDecimal.ZERO, new BigDecimal("50.00"),
                List.of(createTax("2", "4", "15.00", "50.00", "7.50"))
        );
    }

    private RideData.Tax createTax(String taxCode, String rateCode, String rate,
                                    String taxableBase, String amount) {
        return new RideData.Tax(taxCode, rateCode, new BigDecimal(rate),
                new BigDecimal(taxableBase), new BigDecimal(amount));
    }

    private void assertPdfMagicBytes(byte[] pdf) {
        assertTrue(pdf.length >= 4);
        assertTrue(pdf[0] == '%' && pdf[1] == 'P' && pdf[2] == 'D' && pdf[3] == 'F',
                "PDF should start with %PDF magic bytes");
    }
}
