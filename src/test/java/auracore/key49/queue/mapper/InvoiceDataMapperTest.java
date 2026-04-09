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

class InvoiceDataMapperTest {

    private InvoiceDataMapper mapper;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper = new InvoiceDataMapper(objectMapper);
    }

    @Test
    void shouldBuildInvoiceDataFromDocumentAndTenant() {
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
        doc.recipientId = "1712345678";
        doc.recipientName = "Juan Pérez";
        doc.recipientAddress = "Quito, Ecuador";

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");
        var recipient = result.recipient();

        assertEquals("04", recipient.idType());
        assertEquals("1712345678", recipient.id());
        assertEquals("Juan Pérez", recipient.name());
        assertEquals("Quito, Ecuador", recipient.address());
    }

    @Test
    void shouldParseItemsAndComputeDerivedFields() {
        var doc = createTestDocument();
        doc.requestPayload = """
                {
                  "items": [{
                    "main_code": "PROD001",
                    "description": "Test Product",
                    "quantity": 2.0,
                    "unit_price": 10.00,
                    "discount": 0.00,
                    "taxes": [{
                      "code": "2",
                      "rate_code": "4",
                      "rate": 15.00
                    }]
                  }],
                  "payments": [{
                    "payment_method": "01",
                    "total": 23.00
                  }],
                  "additional_info": {
                    "email": "test@example.com"
                  }
                }
                """;

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");

        // Items
        assertEquals(1, result.items().size());
        var item = result.items().getFirst();
        assertEquals("PROD001", item.mainCode());
        assertEquals("Test Product", item.description());
        assertEquals(new BigDecimal("2.0"), item.quantity());
        assertEquals(new BigDecimal("20.00"), item.subtotalBeforeTax());

        // Item taxes — computed from item subtotal
        assertEquals(1, item.taxes().size());
        var tax = item.taxes().getFirst();
        assertEquals("2", tax.taxCode());
        assertEquals("4", tax.rateCode());
        assertEquals(new BigDecimal("20.00"), tax.taxableBase());
        assertEquals(new BigDecimal("3.00"), tax.amount()); // 20 * 15% = 3.00

        // TotalTaxes — aggregated from items
        assertEquals(1, result.totalTaxes().size());
        var totalTax = result.totalTaxes().getFirst();
        assertEquals("2", totalTax.taxCode());
        assertEquals("4", totalTax.rateCode());
        assertEquals(new BigDecimal("20.00"), totalTax.taxableBase());
        assertEquals(new BigDecimal("3.00"), totalTax.amount());

        // Payments
        assertEquals(1, result.payments().size());
        assertEquals("01", result.payments().getFirst().paymentMethod());

        // Additional info
        assertEquals("test@example.com", result.additionalInfo().get("email"));
    }

    @Test
    void shouldAggregateMultipleItemTaxes() {
        var doc = createTestDocument();
        doc.requestPayload = """
                {
                  "items": [
                    {
                      "description": "Item A",
                      "quantity": 1,
                      "unit_price": 100.00,
                      "discount": 0.00,
                      "taxes": [{ "code": "2", "rate_code": "4", "rate": 15.00 }]
                    },
                    {
                      "description": "Item B",
                      "quantity": 2,
                      "unit_price": 50.00,
                      "discount": 10.00,
                      "taxes": [{ "code": "2", "rate_code": "4", "rate": 15.00 }]
                    }
                  ]
                }
                """;

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");

        assertEquals(2, result.items().size());

        // Item A: 1 * 100 - 0 = 100.00
        assertEquals(new BigDecimal("100.00"), result.items().get(0).subtotalBeforeTax());
        // Item B: 2 * 50 - 10 = 90.00
        assertEquals(new BigDecimal("90.00"), result.items().get(1).subtotalBeforeTax());

        // Aggregated total tax: base = 100 + 90 = 190, amount = 15 + 13.50 = 28.50
        assertEquals(1, result.totalTaxes().size());
        var totalTax = result.totalTaxes().getFirst();
        assertEquals("2", totalTax.taxCode());
        assertEquals(new BigDecimal("190.00"), totalTax.taxableBase());
        assertEquals(new BigDecimal("28.50"), totalTax.amount());
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
                { "items": [{ "description": "Minimal Item", "quantity": 1, "unit_price": 5.00,
                  "discount": 0, "taxes": [] }] }
                """;

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");

        assertEquals(1, result.items().size());
        assertEquals(new BigDecimal("5.00"), result.items().getFirst().subtotalBeforeTax());
        assertTrue(result.items().getFirst().taxes().isEmpty());
        assertTrue(result.payments().isEmpty());
        assertTrue(result.totalTaxes().isEmpty());
    }

    @Test
    void shouldThrowForInvalidJson() {
        var doc = createTestDocument();
        doc.requestPayload = "not valid json";

        assertThrows(IllegalArgumentException.class, ()
                -> mapper.build(doc, createTestTenant(),
                        "1234567890123456789012345678901234567890123456789"));
    }

    @Test
    void shouldMapAmountsFromDocument() {
        var doc = createTestDocument();
        doc.subtotalBeforeTax = new BigDecimal("100.00");
        doc.totalDiscount = new BigDecimal("5.00");
        doc.tip = new BigDecimal("2.00");
        doc.totalAmount = new BigDecimal("109.40");
        doc.currency = "DOLAR";

        var result = mapper.build(doc, createTestTenant(),
                "1234567890123456789012345678901234567890123456789");

        assertEquals(new BigDecimal("100.00"), result.subtotalBeforeTax());
        assertEquals(new BigDecimal("5.00"), result.totalDiscount());
        assertEquals(new BigDecimal("2.00"), result.tip());
        assertEquals(new BigDecimal("109.40"), result.totalAmount());
        assertEquals("DOLAR", result.currency());
    }

    private Document createTestDocument() {
        var doc = new Document();
        doc.id = UUID.randomUUID();
        doc.documentType = "01";
        doc.establishment = "001";
        doc.issuePoint = "001";
        doc.sequenceNumber = "000000001";
        doc.issueDate = LocalDate.of(2025, 1, 15);
        doc.recipientIdType = "04";
        doc.recipientId = "1712345678";
        doc.recipientName = "Test Customer";
        doc.recipientAddress = "Test Address";
        doc.subtotalBeforeTax = BigDecimal.ZERO;
        doc.totalDiscount = BigDecimal.ZERO;
        doc.tip = BigDecimal.ZERO;
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
