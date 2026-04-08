package auracore.key49.ride.generator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;


@DisplayName("WaybillRideGenerator")
class WaybillRideGeneratorTest {

    @Test
    @DisplayName("genera PDF válido para guía de remisión")
    void generateProducesValidPdf() {
        var data = buildSampleData(true);
        byte[] pdf = WaybillRideGenerator.generate(data);

        assertPdfMagicBytes(pdf);
    }

    @Test
    @DisplayName("genera PDF con marca de agua cuando no está autorizado")
    void generateWithoutAuthorizationAddsWatermark() {
        var data = buildSampleData(false);
        byte[] pdf = WaybillRideGenerator.generate(data);

        assertPdfMagicBytes(pdf);
    }

    @Test
    @DisplayName("PDF autorizado difiere del no autorizado")
    void authorizedDiffersFromUnauthorized() {
        byte[] authorized = WaybillRideGenerator.generate(buildSampleData(true));
        byte[] unauthorized = WaybillRideGenerator.generate(buildSampleData(false));

        assertNotEquals(authorized.length, unauthorized.length,
                "Authorized and unauthorized PDFs should differ (watermark)");
    }

    @Test
    @DisplayName("genera PDF con múltiples destinatarios")
    void generateWithMultipleAddresseesProducesPdf() {
        var data = buildDataWithMultipleAddressees();
        byte[] pdf = WaybillRideGenerator.generate(data);

        assertPdfMagicBytes(pdf);
    }

    @Test
    @DisplayName("genera PDF sin información adicional")
    void generateWithoutAdditionalInfoProducesPdf() {
        var data = buildSampleData(true);
        var noAdditional = new WaybillRideData(
                data.issuer(), data.accessKey(), data.authorizationNumber(),
                data.authorizationDate(), data.environment(), data.emissionType(),
                data.establishment(), data.issuePoint(), data.sequenceNumber(),
                data.issueDate(), data.departureAddress(),
                data.carrierName(), data.carrierIdType(), data.carrierId(),
                data.transportStartDate(), data.transportEndDate(),
                data.licensePlate(), data.addressees(),
                Map.of(), true, null);

        byte[] pdf = WaybillRideGenerator.generate(noAdditional);
        assertPdfMagicBytes(pdf);
    }

    @Test
    @DisplayName("genera PDF con múltiples ítems por destinatario")
    void generateWithMultipleItemsPerAddressee() {
        var items = List.of(
                new WaybillRideData.ItemSummary("PROD001", "Producto A", new BigDecimal("100")),
                new WaybillRideData.ItemSummary("PROD002", "Producto B", new BigDecimal("50.5")),
                new WaybillRideData.ItemSummary("PROD003", "Producto C", new BigDecimal("25.25")));

        var addr = new WaybillRideData.AddresseeSummary(
                "1790016919001", "CLIENTE TEST CIA. LTDA.",
                "Guayaquil", "Venta", "001-001-000000234", items);

        var data = new WaybillRideData(
                createIssuer(),
                "4904202506179214673900110010010000001230000001231",
                "4904202506179214673900110010010000001230000001231",
                LocalDateTime.of(2025, 4, 15, 10, 30, 0),
                "1", "1", "001", "001", "000000123",
                LocalDate.of(2025, 4, 15),
                "Quito, Bodega Central",
                "TRANSPORTES TEST", "04", "1790016919001",
                LocalDate.of(2025, 4, 15), LocalDate.of(2025, 4, 16),
                "PBB-1234",
                List.of(addr),
                Map.of(), true, null);

        byte[] pdf = WaybillRideGenerator.generate(data);
        assertPdfMagicBytes(pdf);
    }

    // ── Factories ──

    private WaybillRideData buildSampleData(boolean authorized) {
        var items = List.of(
                new WaybillRideData.ItemSummary(
                        "PROD001", "Producto de prueba A", new BigDecimal("100")),
                new WaybillRideData.ItemSummary(
                        "PROD002", "Producto de prueba B", new BigDecimal("50")));

        var addr = new WaybillRideData.AddresseeSummary(
                "1790016919001", "CLIENTE NACIONAL CIA. LTDA.",
                "Guayaquil, Av. 9 de Octubre 100",
                "Venta de mercadería",
                "001-001-000000234", items);

        return new WaybillRideData(
                createIssuer(),
                "4904202506179214673900110010010000001230000001231",
                authorized ? "4904202506179214673900110010010000001230000001231" : null,
                authorized ? LocalDateTime.of(2025, 4, 15, 10, 30, 0) : null,
                "1", "1", "001", "001", "000000123",
                LocalDate.of(2025, 4, 15),
                "Quito, Bodega Central Km 10",
                "TRANSPORTES DEL NORTE CIA. LTDA.", "04", "1790016919001",
                LocalDate.of(2025, 4, 15),
                LocalDate.of(2025, 4, 16),
                "PBB-1234",
                List.of(addr),
                Map.of("Email", "transportes@test.com"),
                authorized, null);
    }

    private WaybillRideData buildDataWithMultipleAddressees() {
        var items1 = List.of(
                new WaybillRideData.ItemSummary(
                        "PROD001", "Producto A", new BigDecimal("100")));
        var items2 = List.of(
                new WaybillRideData.ItemSummary(
                        "PROD002", "Producto B", new BigDecimal("50")),
                new WaybillRideData.ItemSummary(
                        null, "Producto sin código", new BigDecimal("5.5")));

        var addr1 = new WaybillRideData.AddresseeSummary(
                "1790016919001", "CLIENTE UNO CIA. LTDA.",
                "Guayaquil", "Venta", "001-001-000000234", items1);
        var addr2 = new WaybillRideData.AddresseeSummary(
                "1710034065", "Juan Pérez",
                "Cuenca", "Traslado entre bodegas", null, items2);

        return new WaybillRideData(
                createIssuer(),
                "4904202506179214673900110010010000004560000004561",
                "4904202506179214673900110010010000004560000004561",
                LocalDateTime.of(2025, 4, 15, 10, 30, 0),
                "1", "1", "001", "001", "000000456",
                LocalDate.of(2025, 4, 15),
                "Quito, Parque Industrial",
                "TRANSPORTES DEL NORTE CIA. LTDA.", "04", "1790016919001",
                LocalDate.of(2025, 4, 15),
                LocalDate.of(2025, 4, 18),
                "ABC-7890",
                List.of(addr1, addr2),
                Map.of("Email", "transportes@test.com"),
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
