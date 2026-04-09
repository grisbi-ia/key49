package auracore.key49.queue.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.xml.builder.InvoiceXmlBuilder;
import auracore.key49.xml.validation.XsdValidator;

/**
 * Test end-to-end: InvoiceDataMapper → InvoiceXmlBuilder → XsdValidator.
 *
 * <p>
 * Verifica que el XML generado a partir de un requestPayload realista (formato
 * CreateInvoiceRequest con SNAKE_CASE) pasa la validación XSD del SRI para
 * factura v2.1.0.
 */
class InvoiceDataMapperXsdValidationTest {

    private InvoiceDataMapper mapper;

    @BeforeEach
    void setUp() {
        var objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper = new InvoiceDataMapper(objectMapper);
    }

    @Test
    @DisplayName("XML generado desde payload realista pasa validación XSD factura v2.1.0")
    void shouldGenerateXsdValidXmlFromRealisticPayload() {
        var doc = createRealisticDocument();
        var tenant = createRealisticTenant();
        var accessKey = "0804202601179001691900110010010000000421234567811";

        var invoiceData = mapper.build(doc, tenant, accessKey);
        var xml = InvoiceXmlBuilder.build(invoiceData);
        var result = XsdValidator.validate(xml, DocumentType.INVOICE);

        assertTrue(result.valid(),
                "XML debe pasar validación XSD. Errores: " + result.errors());
    }

    @Test
    @DisplayName("XML con múltiples ítems y diferentes impuestos pasa XSD")
    void shouldGenerateXsdValidXmlWithMultipleItemsAndTaxes() {
        var doc = createRealisticDocument();
        doc.requestPayload = """
                {
                  "items": [
                    {
                      "main_code": "SRV-001",
                      "description": "Servicio de hosting mensual",
                      "unit_of_measure": "UNIDAD",
                      "quantity": 1,
                      "unit_price": 50.00,
                      "discount": 0.00,
                      "taxes": [{ "code": "2", "rate_code": "4", "rate": 15.00 }]
                    },
                    {
                      "main_code": "SRV-002",
                      "description": "Dominio .ec renovación anual",
                      "unit_of_measure": "UNIDAD",
                      "quantity": 1,
                      "unit_price": 35.00,
                      "discount": 5.00,
                      "taxes": [{ "code": "2", "rate_code": "4", "rate": 15.00 }]
                    },
                    {
                      "description": "Producto exento IVA",
                      "quantity": 3,
                      "unit_price": 10.00,
                      "discount": 0.00,
                      "taxes": [{ "code": "2", "rate_code": "0", "rate": 0.00 }]
                    }
                  ],
                  "payments": [
                    { "payment_method": "20", "total": 117.50, "term": 0, "time_unit": "dias" }
                  ],
                  "additional_info": {
                    "Dirección": "Av. Principal 123, Quito",
                    "Email": "cliente@example.com"
                  }
                }
                """;
        doc.subtotalBeforeTax = new BigDecimal("110.00");
        doc.totalDiscount = new BigDecimal("5.00");
        doc.totalAmount = new BigDecimal("117.50");

        var invoiceData = mapper.build(doc, createRealisticTenant(),
                "0804202601179001691900110010010000000421234567811");
        var xml = InvoiceXmlBuilder.build(invoiceData);
        var result = XsdValidator.validate(xml, DocumentType.INVOICE);

        assertTrue(result.valid(),
                "XML con múltiples ítems debe pasar validación XSD. Errores: " + result.errors());
    }

    private Document createRealisticDocument() {
        var doc = new Document();
        doc.id = UUID.randomUUID();
        doc.documentType = "01";
        doc.establishment = "001";
        doc.issuePoint = "001";
        doc.sequenceNumber = "000000042";
        doc.issueDate = LocalDate.of(2026, 4, 8);
        doc.recipientIdType = "04";
        doc.recipientId = "1790012345001";
        doc.recipientName = "Empresa Cliente S.A.";
        doc.recipientAddress = "Av. Principal 123, Quito";
        doc.recipientEmail = "contabilidad@cliente.com";
        doc.subtotalBeforeTax = new BigDecimal("80.00");
        doc.totalDiscount = new BigDecimal("0.00");
        doc.tip = BigDecimal.ZERO;
        doc.totalAmount = new BigDecimal("92.00");
        doc.currency = "DOLAR";
        doc.requestPayload = """
                {
                  "items": [{
                    "main_code": "SRV-001",
                    "description": "Servicio de hosting mensual - Plan Business",
                    "unit_of_measure": "UNIDAD",
                    "quantity": 1,
                    "unit_price": 50.00,
                    "discount": 0.00,
                    "taxes": [{ "code": "2", "rate_code": "4", "rate": 15.00 }]
                  },
                  {
                    "main_code": "SRV-002",
                    "description": "Dominio .ec renovación anual",
                    "unit_of_measure": "UNIDAD",
                    "quantity": 1,
                    "unit_price": 35.00,
                    "discount": 5.00,
                    "taxes": [{ "code": "2", "rate_code": "4", "rate": 15.00 }]
                  }],
                  "payments": [{
                    "payment_method": "20",
                    "total": 92.00,
                    "term": 0,
                    "time_unit": "dias"
                  }],
                  "additional_info": {
                    "Dirección": "Av. Principal 123, Quito",
                    "Email": "contabilidad@cliente.com"
                  }
                }
                """;
        return doc;
    }

    private Tenant createRealisticTenant() {
        var tenant = new Tenant();
        tenant.id = UUID.randomUUID();
        tenant.ruc = "1790016919001";
        tenant.legalName = "AuraCore Solutions S.A.";
        tenant.tradeName = "AuraCore";
        tenant.mainAddress = "Quito, Av. República E7-123";
        tenant.requiredAccounting = true;
        tenant.microEnterpriseRegime = false;
        tenant.environment = "test";
        tenant.emissionType = (short) 1;
        tenant.schemaName = "tenant_test";
        tenant.status = "active";
        return tenant;
    }
}
