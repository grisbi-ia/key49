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

@DisplayName("WithholdingDataMapper")
class WithholdingDataMapperTest {

    private WithholdingDataMapper mapper;

    @BeforeEach
    void setUp() {
        var objectMapper = new ObjectMapper();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        objectMapper.registerModule(new JavaTimeModule());
        mapper = new WithholdingDataMapper(objectMapper);
    }

    @Test
    @DisplayName("construye WithholdingData desde Document y Tenant")
    void shouldBuildWithholdingDataFromDocumentAndTenant() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "4904202507179214673900110010010000001230000001231");

        assertNotNull(result);
        assertEquals("4904202507179214673900110010010000001230000001231", result.accessKey());
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
    @DisplayName("mapea sujeto retenido desde Document y payload")
    void shouldMapSubjectFromDocumentAndPayload() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        assertEquals("04", result.subject().idType());
        assertEquals("1790016919001", result.subject().id());
        assertEquals("PROVEEDOR TEST CIA. LTDA.", result.subject().name());
        assertEquals("01", result.subject().subjectType());
    }

    @Test
    @DisplayName("parsea periodo fiscal y parte relacionada desde payload")
    void shouldParseFiscalPeriodAndRelatedParty() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        assertEquals("03/2025", result.fiscalPeriod());
        assertFalse(result.relatedParty());
    }

    @Test
    @DisplayName("parsea documentos de sustento desde payload")
    void shouldParseSupportingDocumentsFromPayload() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        assertNotNull(result.supportingDocuments());
        assertEquals(1, result.supportingDocuments().size());

        var sd = result.supportingDocuments().getFirst();
        assertEquals("01", sd.supportCode());
        assertEquals("01", sd.documentCode());
        assertEquals("001-001-000000234", sd.documentNumber());
        assertEquals("01", sd.paymentLocality());
    }

    @Test
    @DisplayName("parsea retenciones desde payload")
    void shouldParseWithholdingLinesFromPayload() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        var sd = result.supportingDocuments().getFirst();
        assertNotNull(sd.withholdings());
        assertEquals(1, sd.withholdings().size());

        var wh = sd.withholdings().getFirst();
        assertEquals("1", wh.code());
        assertEquals("303", wh.retentionCode());
        assertEquals(0, new BigDecimal("1000.00").compareTo(wh.taxableBase()));
        assertEquals(0, new BigDecimal("10.00").compareTo(wh.retentionRate()));
        assertEquals(0, new BigDecimal("100.00").compareTo(wh.retainedAmount()));
    }

    @Test
    @DisplayName("parsea impuestos del documento de sustento")
    void shouldParseSupportingDocTaxes() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        var sd = result.supportingDocuments().getFirst();
        assertNotNull(sd.taxes());
        assertEquals(1, sd.taxes().size());

        var tax = sd.taxes().getFirst();
        assertEquals("2", tax.taxCode());
        assertEquals("4", tax.rateCode());
    }

    @Test
    @DisplayName("parsea pagos del documento de sustento")
    void shouldParsePaymentsFromPayload() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        var sd = result.supportingDocuments().getFirst();
        assertNotNull(sd.payments());
        assertEquals(1, sd.payments().size());

        var payment = sd.payments().getFirst();
        assertEquals("20", payment.paymentMethod());
        assertEquals(0, new BigDecimal("1150.00").compareTo(payment.total()));
    }

    @Test
    @DisplayName("parsea información adicional desde payload")
    void shouldParseAdditionalInfoFromPayload() {
        var doc = createTestDocument();
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        assertNotNull(result.additionalInfo());
        assertEquals("proveedor@test.com", result.additionalInfo().get("Email"));
    }

    @Test
    @DisplayName("maneja payload vacío sin error")
    void shouldHandleEmptyPayload() {
        var doc = createTestDocument();
        doc.requestPayload = "";
        var tenant = createTestTenant();

        var result = mapper.build(doc, tenant, "accesskey123");

        assertNotNull(result);
        assertTrue(result.supportingDocuments().isEmpty());
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
        doc.documentType = DocumentType.WITHHOLDING.sriCode();
        doc.establishment = "001";
        doc.issuePoint = "001";
        doc.sequenceNumber = "000000123";
        doc.issueDate = LocalDate.of(2025, 4, 15);
        doc.recipientIdType = "04";
        doc.recipientId = "1790016919001";
        doc.recipientName = "PROVEEDOR TEST CIA. LTDA.";
        doc.subtotalBeforeTax = new BigDecimal("1000.00");
        doc.totalAmount = new BigDecimal("100.00");
        doc.vatAmount = BigDecimal.ZERO;
        doc.iceAmount = BigDecimal.ZERO;
        doc.totalDiscount = BigDecimal.ZERO;
        doc.createdAt = Instant.now();
        doc.updatedAt = Instant.now();
        doc.requestPayload = """
                {
                    "subject": {
                        "id_type": "04",
                        "id": "1790016919001",
                        "name": "PROVEEDOR TEST CIA. LTDA.",
                        "subject_type": "01"
                    },
                    "fiscal_period": "03/2025",
                    "related_party": false,
                    "supporting_documents": [
                        {
                            "support_code": "01",
                            "document_code": "01",
                            "document_number": "001-001-000000234",
                            "issue_date": "2025-03-15",
                            "authorization_number": "1503202501179214673900110010010000002340000002341",
                            "payment_locality": "01",
                            "total_without_tax": 1000.00,
                            "total_amount": 1150.00,
                            "taxes": [
                                {
                                    "tax_code": "2",
                                    "rate_code": "4",
                                    "taxable_base": 1000.00,
                                    "rate": 15.00,
                                    "amount": 150.00
                                }
                            ],
                            "withholdings": [
                                {
                                    "code": "1",
                                    "retention_code": "303",
                                    "taxable_base": 1000.00,
                                    "retention_rate": 10.00,
                                    "retained_amount": 100.00
                                }
                            ],
                            "payments": [
                                {
                                    "payment_method": "20",
                                    "total": 1150.00
                                }
                            ]
                        }
                    ],
                    "additional_info": {
                        "Email": "proveedor@test.com"
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
