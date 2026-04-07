package auracore.key49.queue.mapper;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.model.enums.DocumentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;


@DisplayName("WaybillDataMapper")
class WaybillDataMapperTest {

    private WaybillDataMapper mapper;

    @BeforeEach
    void setUp() {
        var objectMapper = new ObjectMapper();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        objectMapper.registerModule(new JavaTimeModule());
        mapper = new WaybillDataMapper(objectMapper);
    }

    @Test
    @DisplayName("construye WaybillData desde Document y Tenant")
    void shouldBuildWaybillDataFromDocumentAndTenant() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant,
                "4904202506179214673900110010010000001230000001231");

        assertNotNull(result);
        assertEquals("4904202506179214673900110010010000001230000001231", result.accessKey());
        assertEquals("001", result.establishment());
        assertEquals("001", result.issuePoint());
        assertEquals("000000123", result.sequenceNumber());
    }

    @Test
    @DisplayName("mapea información del contribuyente desde Tenant")
    void shouldMapTaxpayerInfoFromTenant() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        assertEquals("1", result.taxpayer().environment());
        assertEquals("1", result.taxpayer().emissionType());
        assertEquals("EMPRESA TEST S.A.", result.taxpayer().legalName());
        assertEquals("TEST COMERCIAL", result.taxpayer().tradeName());
        assertEquals("1792146739001", result.taxpayer().ruc());
        assertTrue(result.taxpayer().requiredAccounting());
    }

    @Test
    @DisplayName("mapea código de ambiente producción correctamente")
    void shouldMapProductionEnvironmentCode() {
        var doc = createTestDocument();
        var tenant = createTestTenant();
        tenant.environment = "production";

        var result = mapper.build(doc, tenant, "accesskey123");

        assertEquals("2", result.taxpayer().environment());
    }

    @Test
    @DisplayName("mapea RIMPE para régimen microempresa")
    void shouldSetRimpeForMicroEnterpriseRegime() {
        var doc = createTestDocument();
        var tenant = createTestTenant();
        tenant.microEnterpriseRegime = true;

        var result = mapper.build(doc, tenant, "accesskey123");

        assertEquals("CONTRIBUYENTE RÉGIMEN RIMPE", result.taxpayer().rimpeContributor());
    }

    @Test
    @DisplayName("mapea datos del transportista desde payload")
    void shouldMapCarrierFromPayload() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        assertNotNull(result.carrier());
        assertEquals("04", result.carrier().idType());
        assertEquals("1790016919001", result.carrier().id());
        assertEquals("TRANSPORTES NORTE CIA. LTDA.", result.carrier().name());
    }

    @Test
    @DisplayName("parsea dirección de partida desde payload")
    void shouldParseDepartureAddress() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        assertEquals("Quito, Bodega Central Km 10", result.departureAddress());
    }

    @Test
    @DisplayName("parsea fechas de transporte desde payload")
    void shouldParseTransportDates() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        assertEquals(LocalDate.of(2025, 4, 15), result.transportStartDate());
        assertEquals(LocalDate.of(2025, 4, 16), result.transportEndDate());
    }

    @Test
    @DisplayName("parsea placa desde payload")
    void shouldParseLicensePlate() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        assertEquals("PBB-1234", result.licensePlate());
    }

    @Test
    @DisplayName("parsea destinatarios desde payload")
    void shouldParseAddresseesFromPayload() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        assertNotNull(result.addressees());
        assertEquals(1, result.addressees().size());

        var addr = result.addressees().getFirst();
        assertEquals("1790016919001", addr.id());
        assertEquals("CLIENTE NACIONAL CIA. LTDA.", addr.name());
        assertEquals("Guayaquil, Av. 9 de Octubre", addr.address());
        assertEquals("Venta de mercadería", addr.transferReason());
    }

    @Test
    @DisplayName("parsea ítems de destinatario desde payload")
    void shouldParseItemsFromPayload() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        var addr = result.addressees().getFirst();
        assertNotNull(addr.items());
        assertEquals(1, addr.items().size());

        var item = addr.items().getFirst();
        assertEquals("PROD001", item.mainCode());
        assertEquals("Producto de prueba", item.description());
        assertEquals(0, new BigDecimal("100").compareTo(item.quantity()));
    }

    @Test
    @DisplayName("parsea información adicional desde payload")
    void shouldParseAdditionalInfoFromPayload() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        assertNotNull(result.additionalInfo());
        assertEquals("transportes@test.com", result.additionalInfo().get("Email"));
    }

    @Test
    @DisplayName("maneja payload vacío sin error")
    void shouldHandleEmptyPayload() {
        var doc = createTestDocument();
        doc.requestPayload = "";
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        assertNotNull(result);
        assertTrue(result.addressees().isEmpty());
    }

    @Test
    @DisplayName("lanza excepción con JSON inválido")
    void shouldThrowOnInvalidJson() {
        var doc = createTestDocument();
        doc.requestPayload = "{ invalid json }";
        var tenant = createTestTenant();

        assertThrows(IllegalArgumentException.class,
                () -> mapper.build(doc, tenant, "accesskey123"));
    }

    // ── Factories ──

    private Document createTestDocument() {
        var doc = new Document();
        doc.id = UUID.randomUUID();
        doc.documentType = DocumentType.WAYBILL.sriCode();
        doc.establishment = "001";
        doc.issuePoint = "001";
        doc.sequenceNumber = "000000123";
        doc.issueDate = LocalDate.of(2025, 4, 15);
        doc.recipientIdType = "04";
        doc.recipientId = "1790016919001";
        doc.recipientName = "TRANSPORTES NORTE CIA. LTDA.";
        doc.subtotalBeforeTax = BigDecimal.ZERO;
        doc.totalAmount = BigDecimal.ZERO;
        doc.vatAmount = BigDecimal.ZERO;
        doc.iceAmount = BigDecimal.ZERO;
        doc.totalDiscount = BigDecimal.ZERO;
        doc.createdAt = Instant.now();
        doc.updatedAt = Instant.now();
        doc.requestPayload = """
                {
                    "departure_address": "Quito, Bodega Central Km 10",
                    "carrier": {
                        "id_type": "04",
                        "id": "1790016919001",
                        "name": "TRANSPORTES NORTE CIA. LTDA."
                    },
                    "transport_start_date": "2025-04-15",
                    "transport_end_date": "2025-04-16",
                    "license_plate": "PBB-1234",
                    "addressees": [
                        {
                            "id": "1790016919001",
                            "name": "CLIENTE NACIONAL CIA. LTDA.",
                            "address": "Guayaquil, Av. 9 de Octubre",
                            "transfer_reason": "Venta de mercadería",
                            "destination_establishment": "002",
                            "route": "Quito-Guayaquil",
                            "support_document_code": "01",
                            "support_document_number": "001-001-000000234",
                            "items": [
                                {
                                    "main_code": "PROD001",
                                    "description": "Producto de prueba",
                                    "quantity": 100
                                }
                            ]
                        }
                    ],
                    "additional_info": {
                        "Email": "transportes@test.com"
                    }
                }
                """;
        return doc;
    }

    private Tenant createTestTenant() {
        var tenant = new Tenant();
        tenant.id = UUID.randomUUID();
        tenant.ruc = "1792146739001";
        tenant.legalName = "EMPRESA TEST S.A.";
        tenant.tradeName = "TEST COMERCIAL";
        tenant.mainAddress = "Quito, Av. Amazonas";
        tenant.environment = "test";
        tenant.emissionType = 1;
        tenant.requiredAccounting = true;
        tenant.microEnterpriseRegime = false;
        return tenant;
    }
}
