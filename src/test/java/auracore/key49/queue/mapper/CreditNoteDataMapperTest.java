package auracore.key49.queue.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;

class CreditNoteDataMapperTest {

    private CreditNoteDataMapper mapper;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper = new CreditNoteDataMapper(objectMapper);
    }

    @Test
    void shouldBuildCreditNoteDataFromDocumentAndTenant() {
        var doc = createTestDocument();
        var tenant = createTestTenant();
        var accessKey = "1234567890123456789012345678901234567890123456789";

        var result = mapper.build(doc, tenant, accessKey);

        assertNotNull(result);
        assertEquals(accessKey, result.accessKey());
        assertEquals("001", result.establishment());
        assertEquals("001", result.issuePoint());
        assertEquals("000000001", result.sequenceNumber());
        assertEquals(doc.issueDate, result.issueDate());
    }

    @Test
    void shouldMapTaxpayerInfoFromTenant() {
        var doc = createTestDocument();
        var tenant = createTestTenant();
        tenant.environment = "test";
        tenant.emissionType = 1;
        tenant.legalName = "Company ABC";
        tenant.tradeName = "ABC Store";
        tenant.ruc = "1234567890001";
        tenant.mainAddress = "Main St 123";
        tenant.requiredAccounting = true;
        tenant.specialTaxpayer = "12345";
        tenant.withholdingAgent = "1";
        tenant.microEnterpriseRegime = false;

        var result = mapper.build(doc, tenant, "1234567890123456789012345678901234567890123456789");
        var tp = result.taxpayer();

        assertEquals("1", tp.environment());
        assertEquals("1", tp.emissionType());
        assertEquals("Company ABC", tp.legalName());
        assertEquals("ABC Store", tp.tradeName());
        assertEquals("1234567890001", tp.ruc());
        assertEquals("Main St 123", tp.mainAddress());
        assertTrue(tp.requiredAccounting());
        assertEquals("12345", tp.specialTaxpayer());
        assertEquals("1", tp.withholdingAgent());
        assertNull(tp.rimpeContributor());
    }

    @Test
    void shouldSetRimpeForMicroEnterpriseRegime() {
        var tenant = createTestTenant();
        tenant.microEnterpriseRegime = true;

        var result = mapper.build(createTestDocument(), tenant,
                "1234567890123456789012345678901234567890123456789");

        assertEquals("CONTRIBUYENTE RÉGIMEN RIMPE", result.taxpayer().rimpeContributor());
    }

    @Test
    void shouldMapProductionEnvironmentCode() {
        var tenant = createTestTenant();
        tenant.environment = "production";

        var result = mapper.build(createTestDocument(), tenant,
                "1234567890123456789012345678901234567890123456789");

        assertEquals("2", result.taxpayer().environment());
    }

    @Test
    void shouldMapRecipientFromDocument() {
        var doc = createTestDocument();
        doc.recipientIdType = "04";
        doc.recipientId = "1712345678001";
        doc.recipientName = "Juan Pérez S.A.";

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");
        var recipient = result.recipient();

        assertEquals("04", recipient.idType());
        assertEquals("1712345678001", recipient.id());
        assertEquals("Juan Pérez S.A.", recipient.name());
    }

    @Test
    void shouldParseModifiedDocumentDataFromPayload() {
        var doc = createTestDocument();
        doc.requestPayload = """
                {
                    "modified_document_code": "01",
                    "modified_document_number": "001-001-000000100",
                    "modified_document_date": "2025-06-20",
                    "reason": "Devolución de producto",
                    "items": []
                }
                """;

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");

        assertEquals("01", result.modifiedDocumentCode());
        assertEquals("001-001-000000100", result.modifiedDocumentNumber());
        assertEquals(LocalDate.of(2025, 6, 20), result.modifiedDocumentDate());
        assertEquals("Devolución de producto", result.reason());
    }

    @Test
    void shouldParseItemsFromPayload() {
        var doc = createTestDocument();
        doc.requestPayload = """
                {
                    "modified_document_code": "01",
                    "modified_document_number": "001-001-000000100",
                    "modified_document_date": "2025-06-20",
                    "reason": "Devolución",
                    "items": [{
                        "internal_code": "PROD-001",
                        "additional_code": "7861234567890",
                        "description": "Servicio de hosting",
                        "quantity": 2.0,
                        "unit_price": 50.00,
                        "discount": 0.00,
                        "taxes": [{
                            "code": "2",
                            "rate_code": "4",
                            "rate": 15.00
                        }]
                    }]
                }
                """;

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");

        assertNotNull(result.items());
        assertEquals(1, result.items().size());

        var item = result.items().getFirst();
        assertEquals("PROD-001", item.internalCode());
        assertEquals("7861234567890", item.additionalCode());
        assertEquals("Servicio de hosting", item.description());
        assertEquals(0, new BigDecimal("2.0").compareTo(item.quantity()));
        assertEquals(0, new BigDecimal("50.00").compareTo(item.unitPrice()));
        assertEquals(0, BigDecimal.ZERO.compareTo(item.discount()));
        assertEquals(0, new BigDecimal("100.00").compareTo(item.subtotalBeforeTax()));
    }

    @Test
    void shouldComputeTotalTaxesFromItems() {
        var doc = createTestDocument();
        doc.requestPayload = """
                {
                    "modified_document_code": "01",
                    "modified_document_number": "001-001-000000100",
                    "modified_document_date": "2025-06-20",
                    "reason": "Devolución",
                    "items": [{
                        "internal_code": "PROD-001",
                        "description": "Product A",
                        "quantity": 1.0,
                        "unit_price": 100.00,
                        "discount": 0.00,
                        "taxes": [{
                            "code": "2",
                            "rate_code": "4",
                            "rate": 15.00
                        }]
                    }, {
                        "internal_code": "PROD-002",
                        "description": "Product B",
                        "quantity": 1.0,
                        "unit_price": 50.00,
                        "discount": 0.00,
                        "taxes": [{
                            "code": "2",
                            "rate_code": "4",
                            "rate": 15.00
                        }]
                    }]
                }
                """;

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");

        assertNotNull(result.totalTaxes());
        assertEquals(1, result.totalTaxes().size());

        var tax = result.totalTaxes().getFirst();
        assertEquals("2", tax.taxCode());
        assertEquals("4", tax.rateCode());
        assertEquals(0, new BigDecimal("150.00").compareTo(tax.taxableBase()));
        assertEquals(0, new BigDecimal("22.50").compareTo(tax.amount()));
    }

    @Test
    void shouldHandleEmptyPayload() {
        var doc = createTestDocument();
        doc.requestPayload = null;

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");

        assertNotNull(result);
        assertTrue(result.items().isEmpty());
        assertTrue(result.totalTaxes().isEmpty());
    }

    @Test
    void shouldThrowOnInvalidJson() {
        var doc = createTestDocument();
        doc.requestPayload = "not valid json";

        assertThrows(IllegalArgumentException.class, ()
                -> mapper.build(doc, createTestTenant(),
                        "1234567890123456789012345678901234567890123456789"));
    }

    @Test
    void shouldParseAdditionalInfoFromPayload() {
        var doc = createTestDocument();
        doc.requestPayload = """
                {
                    "modified_document_code": "01",
                    "modified_document_number": "001-001-000000100",
                    "modified_document_date": "2025-06-20",
                    "reason": "Devolución",
                    "items": [],
                    "additional_info": {
                        "Dirección": "Quito",
                        "Email": "test@test.com"
                    }
                }
                """;

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");

        assertNotNull(result.additionalInfo());
        assertEquals(2, result.additionalInfo().size());
        assertEquals("Quito", result.additionalInfo().get("Dirección"));
    }

    // ── Test data factories ──
    private Document createTestDocument() {
        var doc = new Document();
        doc.id = UUID.randomUUID();
        doc.documentType = "04";
        doc.establishment = "001";
        doc.issuePoint = "001";
        doc.sequenceNumber = "000000001";
        doc.issueDate = LocalDate.now();
        doc.recipientIdType = "04";
        doc.recipientId = "1790567890001";
        doc.recipientName = "Client Test";
        doc.subtotalBeforeTax = new BigDecimal("100.00");
        doc.totalAmount = new BigDecimal("115.00");
        doc.currency = "DOLAR";
        doc.requestPayload = """
                {
                    "modified_document_code": "01",
                    "modified_document_number": "001-001-000000100",
                    "modified_document_date": "2025-06-20",
                    "reason": "Devolución de producto",
                    "items": [],
                    "additional_info": {}
                }
                """;
        return doc;
    }

    private Tenant createTestTenant() {
        var tenant = new Tenant();
        tenant.environment = "test";
        tenant.emissionType = 1;
        tenant.legalName = "Test Company S.A.";
        tenant.tradeName = "Test Store";
        tenant.ruc = "1790016919001";
        tenant.mainAddress = "Quito, Av. Test 123";
        tenant.requiredAccounting = true;
        tenant.microEnterpriseRegime = false;
        return tenant;
    }
}
