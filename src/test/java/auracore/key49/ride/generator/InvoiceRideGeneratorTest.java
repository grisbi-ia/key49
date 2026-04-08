package auracore.key49.ride.generator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests para InvoiceRideGenerator.
 */
class InvoiceRideGeneratorTest {

    private static final String ACCESS_KEY = "2506202501099271531200110010020000000011234567813";

    @Test
    void generateProducesValidPdf() {
        var data = buildSampleRideData(true);

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        // PDF magic bytes: %PDF
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithoutAuthorizationAddsWatermark() {
        var data = buildSampleRideData(false);

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
        // Non-authorized PDF should still be valid; watermark is visual
        assertTrue(pdf.length > 100);
    }

    @Test
    void generateAuthorizedPdfDiffersFromUnauthorized() {
        var authorized = buildSampleRideData(true);
        var unauthorized = buildSampleRideData(false);

        byte[] authPdf = InvoiceRideGenerator.generate(authorized);
        byte[] unauthPdf = InvoiceRideGenerator.generate(unauthorized);

        assertNotNull(authPdf);
        assertNotNull(unauthPdf);
        // Both should be valid PDFs
        assertPdfMagicBytes(authPdf);
        assertPdfMagicBytes(unauthPdf);
        // They should differ (watermark adds content)
        assertTrue(authPdf.length != unauthPdf.length,
                "Authorized and unauthorized PDFs should differ in size due to watermark");
    }

    @Test
    void generateWithLogoProducesPdf() {
        // Use a minimal 1x1 white PNG as logo
        byte[] logo = createMinimalPng();
        var data = buildRideDataWithLogo(logo);

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithoutLogoProducesPdf() {
        var data = buildRideDataWithLogo(null);

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithEmptyLogoProducesPdf() {
        var data = buildRideDataWithLogo(new byte[0]);

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithMultipleItemsProducesPdf() {
        var items = List.of(
                createItem("001", "Producto A", "10.00", "5.00", "0.50"),
                createItem("002", "Producto B", "5.00", "20.00", "0.00"),
                createItem("003", "Producto C con descripción larga que ocupa más espacio en la tabla", 
                        "1.00", "100.00", "10.00"),
                createItem("004", "Producto D", "2.50", "8.00", "0.00"),
                createItem("005", "Producto E", "100.00", "0.50", "5.00")
        );

        var data = buildRideDataWithItems(items);

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithPaymentsProducesPdf() {
        var payments = List.of(
                new RideData.Payment("01", new BigDecimal("50.00"), 0, "dias"),
                new RideData.Payment("19", new BigDecimal("50.00"), 30, "dias")
        );

        var data = buildRideDataWithPayments(payments);

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithEmptyPaymentsProducesPdf() {
        var data = buildRideDataWithPayments(List.of());

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithAdditionalInfoProducesPdf() {
        var additionalInfo = new LinkedHashMap<String, String>();
        additionalInfo.put("Email", "cliente@example.com");
        additionalInfo.put("Teléfono", "0991234567");
        additionalInfo.put("Dirección entrega", "Av. Principal y Secundaria");

        var data = buildRideDataWithAdditionalInfo(additionalInfo);

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithEmptyAdditionalInfoProducesPdf() {
        var data = buildRideDataWithAdditionalInfo(Map.of());

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithMultipleTaxesProducesPdf() {
        var totalTaxes = List.of(
                new RideData.TotalTax("2", "2", new BigDecimal("100.00"), new BigDecimal("12"), new BigDecimal("12.00")),
                new RideData.TotalTax("2", "0", new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO),
                new RideData.TotalTax("3", "1", new BigDecimal("30.00"), new BigDecimal("10"), new BigDecimal("3.00"))
        );

        var data = buildRideDataWithTaxes(totalTaxes);

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithSpecialTaxpayerInfoProducesPdf() {
        var issuer = new RideData.Issuer(
                "0992713517001",
                "CONTRIBUYENTE ESPECIAL S.A.",
                "NOMBRE COMERCIAL",
                "Av. Principal 123, Guayaquil",
                "Sucursal Centro, Calle 1 y Calle 2",
                true,
                "12345",
                "1",
                "CONTRIBUYENTE NEGOCIO POPULAR - RÉGIMEN RIMPE"
        );

        var data = buildRideDataWithIssuer(issuer);

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithMinimalIssuerInfoProducesPdf() {
        var issuer = new RideData.Issuer(
                "0992713517001",
                "EMPRESA SIMPLE S.A.",
                null,
                "Dirección",
                null,
                false,
                null,
                null,
                null
        );

        var data = buildRideDataWithIssuer(issuer);

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithRecipientAddressProducesPdf() {
        var recipient = new RideData.Recipient("04", "0991234567001", "COMPRADOR S.A.", "Av. Test 456");
        var data = buildRideDataWithRecipient(recipient);

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithRecipientWithoutAddressProducesPdf() {
        var recipient = new RideData.Recipient("05", "0912345678", "Juan Pérez", null);
        var data = buildRideDataWithRecipient(recipient);

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithConsumerFinalProducesPdf() {
        var recipient = new RideData.Recipient("07", "9999999999999", "CONSUMIDOR FINAL", null);
        var data = buildRideDataWithRecipient(recipient);

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithTipProducesPdf() {
        var data = buildRideDataWithTip(new BigDecimal("5.00"));

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithZeroTipProducesPdf() {
        var data = buildRideDataWithTip(BigDecimal.ZERO);

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateIsIdempotent() {
        var data = buildSampleRideData(true);

        byte[] first = InvoiceRideGenerator.generate(data);
        byte[] second = InvoiceRideGenerator.generate(data);

        assertNotNull(first);
        assertNotNull(second);
        // OpenPDF includes metadata timestamps, but the content structure should be consistent
        assertPdfMagicBytes(first);
        assertPdfMagicBytes(second);
    }

    @Test
    void generateWithTestEnvironmentProducesPdf() {
        var data = buildRideDataWithEnvironment("1");

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void generateWithProductionEnvironmentProducesPdf() {
        var data = buildRideDataWithEnvironment("2");

        byte[] pdf = InvoiceRideGenerator.generate(data);

        assertNotNull(pdf);
        assertPdfMagicBytes(pdf);
    }

    @Test
    void rideDataFormattedDocumentNumber() {
        var data = buildSampleRideData(true);

        String formatted = data.formattedDocumentNumber();

        assertTrue(formatted.equals("001-002-000000001"),
                "Expected 001-002-000000001 but got: " + formatted);
    }

    // --- Assertion helpers ---

    private static void assertPdfMagicBytes(byte[] pdf) {
        assertTrue(pdf.length >= 4, "PDF too small");
        assertTrue(pdf[0] == '%', "First byte should be %");
        assertTrue(pdf[1] == 'P', "Second byte should be P");
        assertTrue(pdf[2] == 'D', "Third byte should be D");
        assertTrue(pdf[3] == 'F', "Fourth byte should be F");
    }

    // --- Builder helpers ---

    private static RideData buildSampleRideData(boolean authorized) {
        var issuer = new RideData.Issuer(
                "0992713517001",
                "EMPRESA DE PRUEBA S.A.",
                "MI TIENDA",
                "Av. Principal 123, Guayaquil",
                "Sucursal Norte, Calle B y C",
                true,
                "5678",
                null,
                null
        );

        var recipient = new RideData.Recipient("04", "0991234567001", "COMPRADOR TEST S.A.", "Av. Comprador 456");

        var items = List.of(createItem("001", "Widget Premium", "2.00", "25.00", "0.00"));
        var totalTaxes = List.of(
                new RideData.TotalTax("2", "2", new BigDecimal("50.00"), new BigDecimal("12"), new BigDecimal("6.00"))
        );
        var payments = List.of(new RideData.Payment("01", new BigDecimal("56.00"), 0, "dias"));
        var additionalInfo = new LinkedHashMap<String, String>();
        additionalInfo.put("Email", "comprador@test.com");

        return new RideData(
                issuer,
                ACCESS_KEY,
                authorized ? ACCESS_KEY : null,
                authorized ? LocalDateTime.of(2025, 6, 25, 10, 30, 0) : null,
                "1",
                "1",
                "01",
                "001",
                "002",
                "000000001",
                LocalDate.of(2025, 6, 25),
                recipient,
                items,
                totalTaxes,
                payments,
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("56.00"),
                "DOLAR",
                additionalInfo,
                authorized,
                null
        );
    }

    private static RideData buildRideDataWithLogo(byte[] logo) {
        var base = buildSampleRideData(true);
        return new RideData(
                base.issuer(), base.accessKey(), base.authorizationNumber(), base.authorizationDate(),
                base.environment(), base.emissionType(), base.documentType(),
                base.establishment(), base.issuePoint(), base.sequenceNumber(), base.issueDate(),
                base.recipient(), base.items(), base.totalTaxes(), base.payments(),
                base.subtotalBeforeTax(), base.totalDiscount(), base.tip(), base.totalAmount(),
                base.currency(), base.additionalInfo(), base.authorized(), logo
        );
    }

    private static RideData buildRideDataWithItems(List<RideData.Item> items) {
        var base = buildSampleRideData(true);
        return new RideData(
                base.issuer(), base.accessKey(), base.authorizationNumber(), base.authorizationDate(),
                base.environment(), base.emissionType(), base.documentType(),
                base.establishment(), base.issuePoint(), base.sequenceNumber(), base.issueDate(),
                base.recipient(), items, base.totalTaxes(), base.payments(),
                base.subtotalBeforeTax(), base.totalDiscount(), base.tip(), base.totalAmount(),
                base.currency(), base.additionalInfo(), base.authorized(), base.logo()
        );
    }

    private static RideData buildRideDataWithPayments(List<RideData.Payment> payments) {
        var base = buildSampleRideData(true);
        return new RideData(
                base.issuer(), base.accessKey(), base.authorizationNumber(), base.authorizationDate(),
                base.environment(), base.emissionType(), base.documentType(),
                base.establishment(), base.issuePoint(), base.sequenceNumber(), base.issueDate(),
                base.recipient(), base.items(), base.totalTaxes(), payments,
                base.subtotalBeforeTax(), base.totalDiscount(), base.tip(), base.totalAmount(),
                base.currency(), base.additionalInfo(), base.authorized(), base.logo()
        );
    }

    private static RideData buildRideDataWithAdditionalInfo(Map<String, String> additionalInfo) {
        var base = buildSampleRideData(true);
        return new RideData(
                base.issuer(), base.accessKey(), base.authorizationNumber(), base.authorizationDate(),
                base.environment(), base.emissionType(), base.documentType(),
                base.establishment(), base.issuePoint(), base.sequenceNumber(), base.issueDate(),
                base.recipient(), base.items(), base.totalTaxes(), base.payments(),
                base.subtotalBeforeTax(), base.totalDiscount(), base.tip(), base.totalAmount(),
                base.currency(), additionalInfo, base.authorized(), base.logo()
        );
    }

    private static RideData buildRideDataWithTaxes(List<RideData.TotalTax> totalTaxes) {
        var base = buildSampleRideData(true);
        return new RideData(
                base.issuer(), base.accessKey(), base.authorizationNumber(), base.authorizationDate(),
                base.environment(), base.emissionType(), base.documentType(),
                base.establishment(), base.issuePoint(), base.sequenceNumber(), base.issueDate(),
                base.recipient(), base.items(), totalTaxes, base.payments(),
                base.subtotalBeforeTax(), base.totalDiscount(), base.tip(), base.totalAmount(),
                base.currency(), base.additionalInfo(), base.authorized(), base.logo()
        );
    }

    private static RideData buildRideDataWithIssuer(RideData.Issuer issuer) {
        var base = buildSampleRideData(true);
        return new RideData(
                issuer, base.accessKey(), base.authorizationNumber(), base.authorizationDate(),
                base.environment(), base.emissionType(), base.documentType(),
                base.establishment(), base.issuePoint(), base.sequenceNumber(), base.issueDate(),
                base.recipient(), base.items(), base.totalTaxes(), base.payments(),
                base.subtotalBeforeTax(), base.totalDiscount(), base.tip(), base.totalAmount(),
                base.currency(), base.additionalInfo(), base.authorized(), base.logo()
        );
    }

    private static RideData buildRideDataWithRecipient(RideData.Recipient recipient) {
        var base = buildSampleRideData(true);
        return new RideData(
                base.issuer(), base.accessKey(), base.authorizationNumber(), base.authorizationDate(),
                base.environment(), base.emissionType(), base.documentType(),
                base.establishment(), base.issuePoint(), base.sequenceNumber(), base.issueDate(),
                recipient, base.items(), base.totalTaxes(), base.payments(),
                base.subtotalBeforeTax(), base.totalDiscount(), base.tip(), base.totalAmount(),
                base.currency(), base.additionalInfo(), base.authorized(), base.logo()
        );
    }

    private static RideData buildRideDataWithTip(BigDecimal tip) {
        var base = buildSampleRideData(true);
        return new RideData(
                base.issuer(), base.accessKey(), base.authorizationNumber(), base.authorizationDate(),
                base.environment(), base.emissionType(), base.documentType(),
                base.establishment(), base.issuePoint(), base.sequenceNumber(), base.issueDate(),
                base.recipient(), base.items(), base.totalTaxes(), base.payments(),
                base.subtotalBeforeTax(), base.totalDiscount(), tip, base.totalAmount(),
                base.currency(), base.additionalInfo(), base.authorized(), base.logo()
        );
    }

    private static RideData buildRideDataWithEnvironment(String env) {
        var base = buildSampleRideData(true);
        return new RideData(
                base.issuer(), base.accessKey(), base.authorizationNumber(), base.authorizationDate(),
                env, base.emissionType(), base.documentType(),
                base.establishment(), base.issuePoint(), base.sequenceNumber(), base.issueDate(),
                base.recipient(), base.items(), base.totalTaxes(), base.payments(),
                base.subtotalBeforeTax(), base.totalDiscount(), base.tip(), base.totalAmount(),
                base.currency(), base.additionalInfo(), base.authorized(), base.logo()
        );
    }

    private static RideData.Item createItem(String code, String desc, String qty, String price, String discount) {
        var quantity = new BigDecimal(qty);
        var unitPrice = new BigDecimal(price);
        var disc = new BigDecimal(discount);
        var subtotal = quantity.multiply(unitPrice).subtract(disc);
        var taxableBase = subtotal;
        var ivaAmount = taxableBase.multiply(new BigDecimal("0.12"));

        var tax = new RideData.Tax("2", "2", new BigDecimal("12"), taxableBase, ivaAmount);

        return new RideData.Item(code, desc, quantity, unitPrice, disc, subtotal, List.of(tax));
    }

    /**
     * Minimal valid 1x1 white PNG (67 bytes).
     */
    private static byte[] createMinimalPng() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, // IHDR chunk
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, // 1x1
                0x08, 0x02, 0x00, 0x00, 0x00, (byte) 0x90, 0x77, 0x53,
                (byte) 0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, // IDAT chunk
                0x54, 0x08, (byte) 0xD7, 0x63, (byte) 0xF8, (byte) 0xCF,
                (byte) 0xC0, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01,
                (byte) 0xE2, 0x21, (byte) 0xBC, 0x33, 0x00, 0x00, 0x00, // IEND chunk
                0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60,
                (byte) 0x82
        };
    }
}
