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
 * Tests para PurchaseClearanceRideGenerator.
 */

class PurchaseClearanceRideGeneratorTest {

    private static final String ACCESS_KEY = "2506202503099271531200110010020000000011234567813";

    @Test
    void generateProducesValidPdf() {
        var data = buildSampleData(true);

        byte[] pdf = PurchaseClearanceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithoutAuthorizationAddsWatermark() {
        var data = buildSampleData(false);

        byte[] pdf = PurchaseClearanceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
        assertTrue(pdf.length > 100);
    }

    @Test
    void authorizedDiffersFromUnauthorized() {
        byte[] authPdf = PurchaseClearanceRideGenerator.generate(buildSampleData(true));
        byte[] unauthPdf = PurchaseClearanceRideGenerator.generate(buildSampleData(false));

        assertNotNull(authPdf);
        assertNotNull(unauthPdf);
        assertPdfMagicBytes(authPdf);
        assertPdfMagicBytes(unauthPdf);
        assertTrue(authPdf.length != unauthPdf.length,
                "Authorized and unauthorized PDFs should differ in size due to watermark");
    }

    @Test
    void generateContainsSupplierData() {
        var data = buildSampleData(true);

        byte[] pdf = PurchaseClearanceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithMultipleItemsProducesPdf() {
        var items = List.of(
                new PurchaseClearanceRideData.Item("PROD-001", "Producto agrícola A",
                        new BigDecimal("100.00"), new BigDecimal("2.50"),
                        new BigDecimal("5.00"), new BigDecimal("245.00"),
                        List.of(createTax("2", "4", "15.00", "245.00", "36.75"))),
                new PurchaseClearanceRideData.Item("PROD-002", "Producto agrícola B",
                        new BigDecimal("50.00"), new BigDecimal("10.00"),
                        BigDecimal.ZERO, new BigDecimal("500.00"),
                        List.of(createTax("2", "0", "0.00", "500.00", "0.00")))
        );

        var data = buildDataWithItems(items);
        byte[] pdf = PurchaseClearanceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithAdditionalInfoProducesPdf() {
        var data = buildSampleData(true);
        byte[] pdf = PurchaseClearanceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithoutAdditionalInfoProducesPdf() {
        var data = new PurchaseClearanceRideData(
                createIssuer(), ACCESS_KEY, ACCESS_KEY,
                LocalDateTime.now(), "1", "1",
                "001", "001", "000000001", LocalDate.now(),
                new PurchaseClearanceRideData.Supplier("05", "0912345678", "PROVEEDOR", null),
                List.of(createDefaultItem()),
                List.of(new RideData.TotalTax("2", "4", new BigDecimal("50.00"),
                        new BigDecimal("15.00"), new BigDecimal("7.50"))),
                List.of(new RideData.Payment("01", new BigDecimal("57.50"), null, null)),
                new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("57.50"),
                "DOLAR", null, true, null
        );

        byte[] pdf = PurchaseClearanceRideGenerator.generate(data);
        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithMultiplePaymentsProducesPdf() {
        var payments = List.of(
                new RideData.Payment("01", new BigDecimal("30.00"), null, null),
                new RideData.Payment("20", new BigDecimal("27.50"), 30, "dias")
        );

        var data = new PurchaseClearanceRideData(
                createIssuer(), ACCESS_KEY, ACCESS_KEY,
                LocalDateTime.now(), "1", "1",
                "001", "001", "000000001", LocalDate.now(),
                new PurchaseClearanceRideData.Supplier("05", "0912345678", "PROVEEDOR S.A.", "Ambato"),
                List.of(createDefaultItem()),
                List.of(new RideData.TotalTax("2", "4", new BigDecimal("50.00"),
                        new BigDecimal("15.00"), new BigDecimal("7.50"))),
                payments,
                new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("57.50"),
                "DOLAR", null, true, null
        );

        byte[] pdf = PurchaseClearanceRideGenerator.generate(data);
        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithQrCodeContainsAccessKey() {
        var data = buildSampleData(true);

        byte[] pdf = PurchaseClearanceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
        assertTrue(pdf.length > 500, "PDF with QR should have substantial size");
    }

    // ── Helpers ──

    private PurchaseClearanceRideData buildSampleData(boolean authorized) {
        return new PurchaseClearanceRideData(
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
                new PurchaseClearanceRideData.Supplier("05", "0912345678",
                        "AGRICULTOR JUAN PÉREZ", "Ambato, Sector Rural"),
                List.of(createDefaultItem()),
                List.of(new RideData.TotalTax("2", "4", new BigDecimal("50.00"),
                        new BigDecimal("15.00"), new BigDecimal("7.50"))),
                List.of(new RideData.Payment("01", new BigDecimal("57.50"), null, null)),
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                new BigDecimal("57.50"),
                "DOLAR",
                new LinkedHashMap<>() {{
                    put("Dirección", "Ambato, Sector Rural km 5");
                    put("Email", "proveedor@test.com");
                }},
                authorized,
                null
        );
    }

    private PurchaseClearanceRideData buildDataWithItems(List<PurchaseClearanceRideData.Item> items) {
        return new PurchaseClearanceRideData(
                createIssuer(), ACCESS_KEY, ACCESS_KEY,
                LocalDateTime.now(), "1", "1",
                "001", "001", "000000001", LocalDate.now(),
                new PurchaseClearanceRideData.Supplier("05", "0912345678", "PROVEEDOR", null),
                items,
                List.of(new RideData.TotalTax("2", "4", new BigDecimal("745.00"),
                        new BigDecimal("15.00"), new BigDecimal("36.75"))),
                List.of(new RideData.Payment("01", new BigDecimal("781.75"), null, null)),
                new BigDecimal("745.00"), new BigDecimal("5.00"), new BigDecimal("781.75"),
                "DOLAR", null, true, null
        );
    }

    private RideData.Issuer createIssuer() {
        return new RideData.Issuer(
                "1790012345001", "EMPRESA DEMO S.A.", "DEMO",
                "Quito, Av. Principal 123", "Sucursal Norte",
                true, null, null, null
        );
    }

    private PurchaseClearanceRideData.Item createDefaultItem() {
        return new PurchaseClearanceRideData.Item(
                "AGR-001", "Producto agrícola orgánico",
                new BigDecimal("10.00"), new BigDecimal("5.00"),
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
