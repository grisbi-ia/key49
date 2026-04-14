package auracore.key49.queue.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.storage.ObjectStorageService;

class RideDataMapperTest {

    private RideDataMapper mapper;

    @BeforeEach
    void setUp() {
        var objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper = new RideDataMapper(objectMapper, new ObjectStorageService());
    }

    @Nested
    @DisplayName("Invoice RIDE")
    class InvoiceRide {

        @Test
        @DisplayName("genera RIDE PDF para factura con items y pagos")
        void shouldGenerateInvoiceRide() {
            var doc = createInvoiceDocument();
            doc.requestPayload = """
                    {
                      "items": [{
                        "main_code": "PROD001",
                        "description": "Producto de prueba",
                        "quantity": 2.0,
                        "unit_price": 10.00,
                        "discount": 0.00,
                        "taxes": [{ "code": "2", "rate_code": "4", "rate": 15.00 }]
                      }],
                      "payments": [{ "payment_method": "01", "total": 23.00 }],
                      "additional_info": { "email": "test@example.com" }
                    }
                    """;

            byte[] pdf = mapper.generateRide(doc, createTestTenant());

            assertNotNull(pdf);
            assertTrue(pdf.length > 0);
            // PDF magic bytes: %PDF
            assertTrue(pdf[0] == 0x25 && pdf[1] == 0x50 && pdf[2] == 0x44 && pdf[3] == 0x46);
        }

        @Test
        @DisplayName("genera RIDE para factura con payload vacío")
        void shouldGenerateRideWithEmptyPayload() {
            var doc = createInvoiceDocument();
            doc.requestPayload = "{}";

            byte[] pdf = mapper.generateRide(doc, createTestTenant());

            assertNotNull(pdf);
            assertTrue(pdf.length > 0);
        }

        @Test
        @DisplayName("genera RIDE para factura con payload null")
        void shouldGenerateRideWithNullPayload() {
            var doc = createInvoiceDocument();
            doc.requestPayload = null;

            byte[] pdf = mapper.generateRide(doc, createTestTenant());

            assertNotNull(pdf);
            assertTrue(pdf.length > 0);
        }
    }

    @Nested
    @DisplayName("Credit Note RIDE")
    class CreditNoteRide {

        @Test
        @DisplayName("genera RIDE PDF para nota de crédito")
        void shouldGenerateCreditNoteRide() {
            var doc = createDocument("04");
            doc.requestPayload = """
                    {
                      "modified_document_code": "01",
                      "modified_document_number": "001-001-000000001",
                      "modified_document_date": "2025-01-10",
                      "reason": "Devolución de mercadería",
                      "items": [{
                        "internal_code": "IC001",
                        "description": "Producto devuelto",
                        "quantity": 1.0,
                        "unit_price": 50.00,
                        "discount": 0.00,
                        "taxes": [{ "code": "2", "rate_code": "4", "rate": 15.00 }]
                      }]
                    }
                    """;

            byte[] pdf = mapper.generateRide(doc, createTestTenant());

            assertNotNull(pdf);
            assertTrue(pdf.length > 0);
            assertTrue(pdf[0] == 0x25 && pdf[1] == 0x50);
        }
    }

    @Nested
    @DisplayName("Debit Note RIDE")
    class DebitNoteRide {

        @Test
        @DisplayName("genera RIDE PDF para nota de débito")
        void shouldGenerateDebitNoteRide() {
            var doc = createDocument("05");
            doc.subtotalBeforeTax = new BigDecimal("100.00");
            doc.totalAmount = new BigDecimal("115.00");
            doc.requestPayload = """
                    {
                      "modified_document_code": "01",
                      "modified_document_number": "001-001-000000001",
                      "modified_document_date": "2025-01-10",
                      "reasons": [{ "description": "Cobro por mora", "amount": 100.00 }],
                      "taxes": [{ "code": "2", "rate_code": "4", "rate": 15.00 }],
                      "payments": [{ "payment_method": "01", "total": 115.00 }]
                    }
                    """;

            byte[] pdf = mapper.generateRide(doc, createTestTenant());

            assertNotNull(pdf);
            assertTrue(pdf.length > 0);
        }
    }

    @Nested
    @DisplayName("Withholding RIDE")
    class WithholdingRide {

        @Test
        @DisplayName("genera RIDE PDF para comprobante de retención")
        void shouldGenerateWithholdingRide() {
            var doc = createDocument("07");
            doc.requestPayload = """
                    {
                      "fiscal_period": "01/2025",
                      "related_party": false,
                      "supporting_documents": [{
                        "support_code": "01",
                        "document_code": "01",
                        "document_number": "001-001-000000001",
                        "issue_date": "2025-01-10",
                        "total_without_tax": 100.00,
                        "total_amount": 115.00,
                        "withholdings": [{
                          "code": "1",
                          "retention_code": "312",
                          "taxable_base": 100.00,
                          "retention_rate": 2.00,
                          "retained_amount": 2.00
                        }],
                        "payments": [{ "payment_method": "01", "total": 115.00 }]
                      }]
                    }
                    """;

            byte[] pdf = mapper.generateRide(doc, createTestTenant());

            assertNotNull(pdf);
            assertTrue(pdf.length > 0);
        }
    }

    @Nested
    @DisplayName("Waybill RIDE")
    class WaybillRide {

        @Test
        @DisplayName("genera RIDE PDF para guía de remisión")
        void shouldGenerateWaybillRide() {
            var doc = createDocument("06");
            doc.requestPayload = """
                    {
                      "departure_address": "Guayaquil, Av. Principal",
                      "carrier": { "id_type": "04", "id": "0912345678", "name": "Transportista ABC" },
                      "transport_start_date": "2025-01-15",
                      "transport_end_date": "2025-01-16",
                      "license_plate": "GYE-1234",
                      "addressees": [{
                        "id": "1712345678",
                        "name": "Destinatario XYZ",
                        "address": "Quito, Av. Secundaria",
                        "transfer_reason": "Venta",
                        "support_document_number": "001-001-000000001",
                        "items": [{
                          "main_code": "GR001",
                          "description": "Producto transportado",
                          "quantity": 10
                        }]
                      }]
                    }
                    """;

            byte[] pdf = mapper.generateRide(doc, createTestTenant());

            assertNotNull(pdf);
            assertTrue(pdf.length > 0);
        }
    }

    @Nested
    @DisplayName("Purchase Clearance RIDE")
    class PurchaseClearanceRide {

        @Test
        @DisplayName("genera RIDE PDF para liquidación de compra")
        void shouldGeneratePurchaseClearanceRide() {
            var doc = createDocument("03");
            doc.requestPayload = """
                    {
                      "items": [{
                        "main_code": "LC001",
                        "description": "Servicio adquirido",
                        "quantity": 1.0,
                        "unit_price": 200.00,
                        "discount": 0.00,
                        "taxes": [{ "code": "2", "rate_code": "4", "rate": 15.00 }]
                      }],
                      "payments": [{ "payment_method": "01", "total": 230.00 }]
                    }
                    """;

            byte[] pdf = mapper.generateRide(doc, createTestTenant());

            assertNotNull(pdf);
            assertTrue(pdf.length > 0);
        }
    }

    @Nested
    @DisplayName("Error cases")
    class ErrorCases {

        @Test
        @DisplayName("JSON inválido lanza IllegalArgumentException")
        void shouldThrowForInvalidJson() {
            var doc = createInvoiceDocument();
            doc.requestPayload = "not valid json {{{";

            assertThrows(IllegalArgumentException.class,
                    () -> mapper.generateRide(doc, createTestTenant()));
        }

        @Test
        @DisplayName("tipo de documento desconocido lanza excepción")
        void shouldThrowForUnknownDocumentType() {
            var doc = createDocument("99");

            assertThrows(IllegalArgumentException.class,
                    () -> mapper.generateRide(doc, createTestTenant()));
        }
    }

    // ── Helpers ──
    private Document createInvoiceDocument() {
        return createDocument("01");
    }

    private Document createDocument(String docType) {
        var doc = new Document();
        doc.id = UUID.randomUUID();
        doc.documentType = docType;
        doc.establishment = "001";
        doc.issuePoint = "001";
        doc.sequenceNumber = "000000001";
        doc.issueDate = LocalDate.of(2025, 1, 15);
        doc.accessKey = "1504202501179214673900110010010000000010000000112";
        doc.authorizationNumber = "1504202501179214673900110010010000000010000000112";
        doc.authorizationDate = Instant.parse("2025-01-15T20:00:00Z");
        doc.status = DocumentStatus.AUTHORIZED;
        doc.recipientIdType = "04";
        doc.recipientId = "1712345678";
        doc.recipientName = "Cliente de Prueba";
        doc.recipientAddress = "Quito, Ecuador";
        doc.recipientEmail = "cliente@example.com";
        doc.subtotalBeforeTax = new BigDecimal("20.00");
        doc.totalDiscount = BigDecimal.ZERO;
        doc.tip = BigDecimal.ZERO;
        doc.totalAmount = new BigDecimal("23.00");
        doc.currency = "DOLAR";
        doc.requestPayload = "{}";
        return doc;
    }

    private Tenant createTestTenant() {
        var tenant = new Tenant();
        tenant.id = UUID.randomUUID();
        tenant.ruc = "1792146739001";
        tenant.legalName = "Empresa de Prueba S.A.";
        tenant.tradeName = "Prueba Store";
        tenant.mainAddress = "Guayaquil, Ecuador";
        tenant.requiredAccounting = true;
        tenant.microEnterpriseRegime = false;
        tenant.environment = "test";
        tenant.emissionType = 1;
        tenant.schemaName = "tenant_test";
        tenant.status = "active";
        return tenant;
    }
}
