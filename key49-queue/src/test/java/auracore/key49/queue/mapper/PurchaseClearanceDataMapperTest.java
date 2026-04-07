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
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;


class PurchaseClearanceDataMapperTest {

    private PurchaseClearanceDataMapper mapper;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        mapper = new PurchaseClearanceDataMapper(objectMapper);
    }

    @Test
    void shouldBuildPurchaseClearanceDataFromDocumentAndTenant() {
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
    void shouldMapSupplierFromDocument() {
        var doc = createTestDocument();
        doc.recipientIdType = "05";
        doc.recipientId = "1712345678";
        doc.recipientName = "María López";
        doc.recipientAddress = "Guayaquil, Ecuador";

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");
        var supplier = result.supplier();

        assertEquals("05", supplier.idType());
        assertEquals("1712345678", supplier.id());
        assertEquals("María López", supplier.name());
        assertEquals("Guayaquil, Ecuador", supplier.address());
    }

    @Test
    void shouldParseItemsFromRequestPayload() {
        var doc = createTestDocument();
        doc.requestPayload = """
                {
                  "items": [{
                    "mainCode": "CACAO001",
                    "description": "Cacao en grano",
                    "quantity": 50.0,
                    "unitPrice": 1.50,
                    "discount": 0.00,
                    "subtotalBeforeTax": 75.00,
                    "taxes": [{
                      "taxCode": "2",
                      "rateCode": "4",
                      "rate": 15.00,
                      "taxableBase": 75.00,
                      "amount": 11.25
                    }]
                  }],
                  "totalTaxes": [{
                    "taxCode": "2",
                    "rateCode": "4",
                    "taxableBase": 75.00,
                    "rate": 15.00,
                    "amount": 11.25
                  }],
                  "payments": [{
                    "paymentMethod": "01",
                    "total": 86.25
                  }],
                  "additionalInfo": {
                    "email": "proveedor@example.com"
                  }
                }
                """;

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");

        assertEquals(1, result.items().size());
        assertEquals("CACAO001", result.items().getFirst().mainCode());
        assertEquals("Cacao en grano", result.items().getFirst().description());
        assertEquals(new BigDecimal("50.0"), result.items().getFirst().quantity());
        assertEquals(1, result.items().getFirst().taxes().size());

        assertEquals(1, result.totalTaxes().size());
        assertEquals("2", result.totalTaxes().getFirst().taxCode());

        assertEquals(1, result.payments().size());
        assertEquals("01", result.payments().getFirst().paymentMethod());

        assertEquals("proveedor@example.com", result.additionalInfo().get("email"));
    }

    @Test
    void shouldHandleNullRequestPayload() {
        var doc = createTestDocument();
        doc.requestPayload = null;

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");

        assertNotNull(result.items());
        assertTrue(result.items().isEmpty());
        assertNotNull(result.payments());
        assertTrue(result.payments().isEmpty());
        assertNotNull(result.totalTaxes());
        assertTrue(result.totalTaxes().isEmpty());
        assertNotNull(result.additionalInfo());
        assertTrue(result.additionalInfo().isEmpty());
    }

    @Test
    void shouldHandleEmptyRequestPayload() {
        var doc = createTestDocument();
        doc.requestPayload = "";

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");

        assertTrue(result.items().isEmpty());
    }

    @Test
    void shouldHandlePartialRequestPayload() {
        var doc = createTestDocument();
        doc.requestPayload = """
                { "items": [{ "description": "Leche cruda", "quantity": 100, "unitPrice": 0.50,
                  "discount": 0, "subtotalBeforeTax": 50.00, "taxes": [] }] }
                """;

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");

        assertEquals(1, result.items().size());
        assertTrue(result.payments().isEmpty());
        assertTrue(result.totalTaxes().isEmpty());
    }

    @Test
    void shouldThrowForInvalidJson() {
        var doc = createTestDocument();
        doc.requestPayload = "not valid json";

        assertThrows(IllegalArgumentException.class, () ->
                mapper.build(doc, createTestTenant(),
                        "1234567890123456789012345678901234567890123456789"));
    }

    @Test
    void shouldMapAmountsFromDocument() {
        var doc = createTestDocument();
        doc.subtotalBeforeTax = new BigDecimal("75.00");
        doc.totalDiscount = new BigDecimal("0.00");
        doc.totalAmount = new BigDecimal("86.25");
        doc.currency = "DOLAR";

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");

        assertEquals(new BigDecimal("75.00"), result.subtotalBeforeTax());
        assertEquals(new BigDecimal("0.00"), result.totalDiscount());
        assertEquals(new BigDecimal("86.25"), result.totalAmount());
        assertEquals("DOLAR", result.currency());
    }

    private Document createTestDocument() {
        var doc = new Document();
        doc.id = UUID.randomUUID();
        doc.documentType = "03";
        doc.establishment = "001";
        doc.issuePoint = "001";
        doc.sequenceNumber = "000000001";
        doc.issueDate = LocalDate.of(2025, 1, 15);
        doc.recipientIdType = "05";
        doc.recipientId = "1712345678";
        doc.recipientName = "Proveedor Test";
        doc.recipientAddress = "Test Address";
        doc.subtotalBeforeTax = BigDecimal.ZERO;
        doc.totalDiscount = BigDecimal.ZERO;
        doc.totalAmount = BigDecimal.ZERO;
        doc.currency = "DOLAR";
        return doc;
    }

    private Tenant createTestTenant() {
        var tenant = new Tenant();
        tenant.id = UUID.randomUUID();
        tenant.ruc = "1234567890001";
        tenant.legalName = "Test Company";
        tenant.tradeName = "Test Store";
        tenant.mainAddress = "Test Address 123";
        tenant.requiredAccounting = false;
        tenant.microEnterpriseRegime = false;
        tenant.environment = "test";
        tenant.emissionType = 1;
        tenant.schemaName = "tenant_test";
        tenant.status = "active";
        return tenant;
    }
}
