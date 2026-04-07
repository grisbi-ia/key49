package auracore.key49.ride.generator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;


@DisplayName("WithholdingRideGenerator")
class WithholdingRideGeneratorTest {

    @Test
    @DisplayName("genera PDF válido para comprobante de retención")
    void generateProducesValidPdf() {
        var data = buildSampleData(true);
        byte[] pdf = WithholdingRideGenerator.generate(data);

        assertPdfMagicBytes(pdf);
    }

    @Test
    @DisplayName("genera PDF con marca de agua cuando no está autorizado")
    void generateWithoutAuthorizationAddsWatermark() {
        var data = buildSampleData(false);
        byte[] pdf = WithholdingRideGenerator.generate(data);

        assertPdfMagicBytes(pdf);
    }

    @Test
    @DisplayName("PDF autorizado difiere del no autorizado")
    void authorizedDiffersFromUnauthorized() {
        byte[] authorized = WithholdingRideGenerator.generate(buildSampleData(true));
        byte[] unauthorized = WithholdingRideGenerator.generate(buildSampleData(false));

        assertNotEquals(authorized.length, unauthorized.length,
                "Authorized and unauthorized PDFs should differ (watermark)");
    }

    @Test
    @DisplayName("genera PDF con múltiples documentos de sustento")
    void generateWithMultipleSupportingDocsProducesPdf() {
        var data = buildDataWithMultipleDocs();
        byte[] pdf = WithholdingRideGenerator.generate(data);

        assertPdfMagicBytes(pdf);
    }

    @Test
    @DisplayName("genera PDF sin información adicional")
    void generateWithoutAdditionalInfoProducesPdf() {
        var data = buildSampleData(true);
        var noAdditional = new WithholdingRideData(
                data.issuer(), data.accessKey(), data.authorizationNumber(),
                data.authorizationDate(), data.environment(), data.emissionType(),
                data.establishment(), data.issuePoint(), data.sequenceNumber(),
                data.issueDate(), data.subject(), data.fiscalPeriod(),
                data.relatedParty(), data.supportingDocuments(),
                data.totalRetained(), Map.of(), true, null);

        byte[] pdf = WithholdingRideGenerator.generate(noAdditional);
        assertPdfMagicBytes(pdf);
    }

    @Test
    @DisplayName("genera PDF con retenciones IVA, Renta e ISD")
    void generateWithAllRetentionTypesProducesPdf() {
        var withholdings = List.of(
                new WithholdingRideData.WithholdingLineSummary(
                        "1", "303", new BigDecimal("1000.00"),
                        new BigDecimal("10.00"), new BigDecimal("100.00")),
                new WithholdingRideData.WithholdingLineSummary(
                        "2", "725", new BigDecimal("150.00"),
                        new BigDecimal("30.00"), new BigDecimal("45.00")),
                new WithholdingRideData.WithholdingLineSummary(
                        "6", "4580", new BigDecimal("2000.00"),
                        new BigDecimal("5.00"), new BigDecimal("100.00")));

        var sd = new WithholdingRideData.SupportingDocumentSummary(
                "01", "01", "001-001-000000234",
                LocalDate.of(2025, 3, 15),
                new BigDecimal("1000.00"), new BigDecimal("1150.00"),
                withholdings, List.of());

        var data = new WithholdingRideData(
                createIssuer(), "4904202507179214673900110010010000001230000001231",
                "4904202507179214673900110010010000001230000001231",
                LocalDateTime.of(2025, 4, 15, 10, 30, 0),
                "1", "1", "001", "001", "000000123",
                LocalDate.of(2025, 4, 15),
                new RideData.Recipient("04", "1790016919001",
                        "PROVEEDOR TEST CIA. LTDA.", null),
                "03/2025", false,
                List.of(sd),
                new BigDecimal("245.00"),
                Map.of(), true, null);

        byte[] pdf = WithholdingRideGenerator.generate(data);
        assertPdfMagicBytes(pdf);
    }

    // ── Factories ──

    private WithholdingRideData buildSampleData(boolean authorized) {
        var withholdings = List.of(
                new WithholdingRideData.WithholdingLineSummary(
                        "1", "303", new BigDecimal("1000.00"),
                        new BigDecimal("10.00"), new BigDecimal("100.00")),
                new WithholdingRideData.WithholdingLineSummary(
                        "2", "725", new BigDecimal("150.00"),
                        new BigDecimal("30.00"), new BigDecimal("45.00")));

        var payments = List.of(
                new RideData.Payment("20", new BigDecimal("1150.00"), null, null));

        var sd = new WithholdingRideData.SupportingDocumentSummary(
                "01", "01", "001-001-000000234",
                LocalDate.of(2025, 3, 15),
                new BigDecimal("1000.00"), new BigDecimal("1150.00"),
                withholdings, payments);

        return new WithholdingRideData(
                createIssuer(),
                "4904202507179214673900110010010000001230000001231",
                authorized ? "4904202507179214673900110010010000001230000001231" : null,
                authorized ? LocalDateTime.of(2025, 4, 15, 10, 30, 0) : null,
                "1", "1", "001", "001", "000000123",
                LocalDate.of(2025, 4, 15),
                new RideData.Recipient("04", "1790016919001",
                        "PROVEEDOR TEST CIA. LTDA.", null),
                "03/2025", false,
                List.of(sd),
                new BigDecimal("145.00"),
                Map.of("Email", "proveedor@test.com"),
                authorized, null);
    }

    private WithholdingRideData buildDataWithMultipleDocs() {
        var wh1 = List.of(
                new WithholdingRideData.WithholdingLineSummary(
                        "1", "303", new BigDecimal("1000.00"),
                        new BigDecimal("10.00"), new BigDecimal("100.00")));
        var wh2 = List.of(
                new WithholdingRideData.WithholdingLineSummary(
                        "1", "312", new BigDecimal("500.00"),
                        new BigDecimal("8.00"), new BigDecimal("40.00")));

        var sd1 = new WithholdingRideData.SupportingDocumentSummary(
                "01", "01", "001-001-000000234",
                LocalDate.of(2025, 3, 15),
                new BigDecimal("1000.00"), new BigDecimal("1150.00"),
                wh1, List.of());
        var sd2 = new WithholdingRideData.SupportingDocumentSummary(
                "01", "01", "001-001-000000100",
                LocalDate.of(2025, 3, 10),
                new BigDecimal("500.00"), new BigDecimal("500.00"),
                wh2, List.of());

        return new WithholdingRideData(
                createIssuer(),
                "4904202507179214673900110010010000004560000004561",
                "4904202507179214673900110010010000004560000004561",
                LocalDateTime.of(2025, 4, 15, 10, 30, 0),
                "1", "1", "001", "001", "000000456",
                LocalDate.of(2025, 4, 15),
                new RideData.Recipient("04", "1790016919001",
                        "PROVEEDOR TEST CIA. LTDA.", null),
                "03/2025", true,
                List.of(sd1, sd2),
                new BigDecimal("140.00"),
                Map.of("Email", "proveedor@test.com"),
                true, null);
    }

    private RideData.Issuer createIssuer() {
        return new RideData.Issuer(
                "1792146739001", "EMPRESA DE PRUEBAS S.A.",
                "PRUEBAS COMERCIAL", "Quito, Av. Amazonas N24-345",
                "Quito, Sucursal Norte", true,
                "12345", "1", "CONTRIBUYENTE RÉGIMEN RIMPE");
    }

    private void assertPdfMagicBytes(byte[] pdf) {
        assertNotNull(pdf);
        assertTrue(pdf.length > 100, "PDF should be at least 100 bytes");
        assertEquals((byte) '%', pdf[0]);
        assertEquals((byte) 'P', pdf[1]);
        assertEquals((byte) 'D', pdf[2]);
        assertEquals((byte) 'F', pdf[3]);
    }
}
