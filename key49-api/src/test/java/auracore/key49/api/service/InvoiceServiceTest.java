package auracore.key49.api.service;

import auracore.key49.api.dto.CreateInvoiceRequest;
import auracore.key49.api.dto.CreateInvoiceRequest.ItemRequest;
import auracore.key49.api.dto.CreateInvoiceRequest.PaymentRequest;
import auracore.key49.api.dto.CreateInvoiceRequest.RecipientRequest;
import auracore.key49.api.dto.CreateInvoiceRequest.TaxRequest;
import auracore.key49.api.exception.BusinessException;
import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.Document;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.core.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para InvoiceService: validación y cálculo de totales.
 */
class InvoiceServiceTest {

    InvoiceService service;

    @BeforeEach
    void setUp() {
        service = new InvoiceService();
        // ObjectMapper is needed for some internal operations but not for validation/totals
        var om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        // Inject via reflection for unit testing (fields are package-private via @Inject)
        try {
            var omField = InvoiceService.class.getDeclaredField("objectMapper");
            omField.setAccessible(true);
            omField.set(service, om);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Helpers ──

    private RecipientRequest validRecipient() {
        return new RecipientRequest("04", "1790016919001", "Empresa S.A.", "Quito", "test@test.com", "0991234567");
    }

    private RecipientRequest recipientWithCedula() {
        return new RecipientRequest("05", "1710034065", "Juan Pérez", null, "juan@test.com", null);
    }

    private ItemRequest validItem() {
        return new ItemRequest(
                "PROD-001", null, "Servicio de hosting", "UNIDAD",
                BigDecimal.ONE, new BigDecimal("50.00"), BigDecimal.ZERO,
                List.of(new TaxRequest("2", "4", new BigDecimal("15"))));
    }

    private ItemRequest itemWithVat0() {
        return new ItemRequest(
                "PROD-002", null, "Producto exento", "UNIDAD",
                new BigDecimal("2"), new BigDecimal("100.00"), BigDecimal.ZERO,
                List.of(new TaxRequest("2", "0", BigDecimal.ZERO)));
    }

    private PaymentRequest validPayment() {
        return new PaymentRequest("20", new BigDecimal("57.50"), 0, "days");
    }

    private CreateInvoiceRequest validRequest() {
        return new CreateInvoiceRequest(
                "001", "001", "000000042",
                LocalDate.now(Key49Constants.EC_ZONE),
                validRecipient(),
                List.of(validItem()),
                List.of(validPayment()),
                Map.of("Dirección", "Quito"));
    }

    // ── Validation Tests ──

    @Nested
    class ValidationTests {

        @Test
        void validRequest_passes() {
            assertDoesNotThrow(() -> service.validateCreateRequest(validRequest()));
        }

        @Test
        void invalidEstablishment_fails() {
            var req = new CreateInvoiceRequest(
                    "01", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), List.of(validItem()), List.of(validPayment()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertEquals("VALIDATION_ERROR", ex.code());
            assertTrue(ex.details().stream().anyMatch(d -> "establishment".equals(d.field())));
        }

        @Test
        void invalidIssuePoint_fails() {
            var req = new CreateInvoiceRequest(
                    "001", "1", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), List.of(validItem()), List.of(validPayment()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "issue_point".equals(d.field())));
        }

        @Test
        void invalidSequenceNumber_fails() {
            var req = new CreateInvoiceRequest(
                    "001", "001", "42",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), List.of(validItem()), List.of(validPayment()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "sequence_number".equals(d.field())));
        }

        @Test
        void wrongIssueDate_fails() {
            var req = new CreateInvoiceRequest(
                    "001", "001", "000000042",
                    LocalDate.of(2020, 1, 1),
                    validRecipient(), List.of(validItem()), List.of(validPayment()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "issue_date".equals(d.field())));
        }

        @Test
        void nullIssueDate_fails() {
            var req = new CreateInvoiceRequest(
                    "001", "001", "000000042",
                    null,
                    validRecipient(), List.of(validItem()), List.of(validPayment()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "issue_date".equals(d.field())));
        }

        @Test
        void nullRecipient_fails() {
            var req = new CreateInvoiceRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    null, List.of(validItem()), List.of(validPayment()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "recipient".equals(d.field())));
        }

        @Test
        void invalidRecipientIdType_fails() {
            var recipient = new RecipientRequest("99", "1234567890", "Test", null, null, null);
            var req = new CreateInvoiceRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    recipient, List.of(validItem()), List.of(validPayment()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> d.field().contains("recipient.id_type")));
        }

        @Test
        void invalidRuc_fails() {
            var recipient = new RecipientRequest("04", "1234567890123", "Test", null, null, null);
            var req = new CreateInvoiceRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    recipient, List.of(validItem()), List.of(validPayment()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> d.field().contains("recipient.id")));
        }

        @Test
        void validCedulaRecipient_passes() {
            var req = new CreateInvoiceRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    recipientWithCedula(), List.of(validItem()), List.of(validPayment()), null);

            assertDoesNotThrow(() -> service.validateCreateRequest(req));
        }

        @Test
        void emptyItems_fails() {
            var req = new CreateInvoiceRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), List.of(), List.of(validPayment()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "items".equals(d.field())));
        }

        @Test
        void itemWithoutDescription_fails() {
            var item = new ItemRequest("P1", null, null, "U", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, null);
            var req = new CreateInvoiceRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), List.of(item), List.of(validPayment()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> d.field().contains("description")));
        }

        @Test
        void itemWithZeroQuantity_fails() {
            var item = new ItemRequest("P1", null, "Test", "U", BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO, null);
            var req = new CreateInvoiceRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), List.of(item), List.of(validPayment()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> d.field().contains("quantity")));
        }

        @Test
        void invalidTaxCode_fails() {
            var tax = new TaxRequest("9", "0", BigDecimal.ZERO);
            var item = new ItemRequest("P1", null, "Test", "U", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, List.of(tax));
            var req = new CreateInvoiceRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), List.of(item), List.of(validPayment()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> d.field().contains("taxes[0].code")));
        }

        @Test
        void invalidVatRateCode_fails() {
            var tax = new TaxRequest("2", "99", new BigDecimal("15"));
            var item = new ItemRequest("P1", null, "Test", "U", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, List.of(tax));
            var req = new CreateInvoiceRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), List.of(item), List.of(validPayment()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> d.field().contains("rate_code")));
        }

        @Test
        void emptyPayments_fails() {
            var req = new CreateInvoiceRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), List.of(validItem()), List.of(), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "payments".equals(d.field())));
        }

        @Test
        void invalidPaymentMethod_fails() {
            var payment = new PaymentRequest("99", BigDecimal.TEN, 0, "days");
            var req = new CreateInvoiceRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), List.of(validItem()), List.of(payment), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> d.field().contains("payment_method")));
        }

        @Test
        void multipleErrors_collected() {
            var req = new CreateInvoiceRequest(
                    "01", "1", "42",
                    LocalDate.of(2020, 1, 1),
                    null, null, null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertEquals(400, ex.httpStatus());
            assertTrue(ex.details().size() >= 4, "Should collect multiple validation errors");
        }

        @Test
        void finalConsumer_validatesCorrectly() {
            var recipient = new RecipientRequest("07", "9999999999999", "CONSUMIDOR FINAL", null, null, null);
            var req = new CreateInvoiceRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    recipient, List.of(validItem()), List.of(validPayment()), null);

            assertDoesNotThrow(() -> service.validateCreateRequest(req));
        }

        @Test
        void passport_validatesCorrectly() {
            var recipient = new RecipientRequest("06", "AB12345", "John Doe", null, "john@test.com", null);
            var req = new CreateInvoiceRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    recipient, List.of(validItem()), List.of(validPayment()), null);

            assertDoesNotThrow(() -> service.validateCreateRequest(req));
        }
    }

    // ── Total Computation Tests ──

    @Nested
    class TotalComputationTests {

        @Test
        void singleItemWithVat15() {
            var tax = new TaxRequest("2", "4", new BigDecimal("15"));
            var item = new ItemRequest("P1", null, "Hosting", "U",
                    BigDecimal.ONE, new BigDecimal("50.00"), BigDecimal.ZERO, List.of(tax));

            var doc = new Document();
            service.computeAndSetTotals(doc, List.of(item));

            assertEquals(new BigDecimal("50.00"), doc.subtotalBeforeTax);
            assertEquals(new BigDecimal("50.00"), doc.subtotalVat15);
            assertEquals(new BigDecimal("7.50"), doc.vatAmount);
            assertEquals(new BigDecimal("57.50"), doc.totalAmount);
            assertEquals(new BigDecimal("0.00"), doc.totalDiscount);
            assertEquals(new BigDecimal("0.00"), doc.iceAmount);
        }

        @Test
        void singleItemWithVat12() {
            var tax = new TaxRequest("2", "2", new BigDecimal("12"));
            var item = new ItemRequest("P1", null, "Producto", "U",
                    BigDecimal.ONE, new BigDecimal("100.00"), BigDecimal.ZERO, List.of(tax));

            var doc = new Document();
            service.computeAndSetTotals(doc, List.of(item));

            assertEquals(new BigDecimal("100.00"), doc.subtotalBeforeTax);
            assertEquals(new BigDecimal("100.00"), doc.subtotalVat12);
            assertEquals(new BigDecimal("12.00"), doc.vatAmount);
            assertEquals(new BigDecimal("112.00"), doc.totalAmount);
        }

        @Test
        void singleItemWithVat0() {
            var tax = new TaxRequest("2", "0", BigDecimal.ZERO);
            var item = new ItemRequest("P1", null, "Medicina", "U",
                    new BigDecimal("3"), new BigDecimal("10.00"), BigDecimal.ZERO, List.of(tax));

            var doc = new Document();
            service.computeAndSetTotals(doc, List.of(item));

            assertEquals(new BigDecimal("30.00"), doc.subtotalBeforeTax);
            assertEquals(new BigDecimal("30.00"), doc.subtotalVat0);
            assertEquals(new BigDecimal("0.00"), doc.vatAmount);
            assertEquals(new BigDecimal("30.00"), doc.totalAmount);
        }

        @Test
        void itemWithDiscount() {
            var tax = new TaxRequest("2", "4", new BigDecimal("15"));
            var item = new ItemRequest("P1", null, "Producto", "U",
                    new BigDecimal("2"), new BigDecimal("100.00"), new BigDecimal("20.00"), List.of(tax));

            var doc = new Document();
            service.computeAndSetTotals(doc, List.of(item));

            // lineTotal = 2 * 100 = 200, discount = 20, discountedTotal = 180
            assertEquals(new BigDecimal("180.00"), doc.subtotalBeforeTax);
            assertEquals(new BigDecimal("20.00"), doc.totalDiscount);
            assertEquals(new BigDecimal("180.00"), doc.subtotalVat15);
            assertEquals(new BigDecimal("27.00"), doc.vatAmount); // 180 * 15% = 27
            assertEquals(new BigDecimal("207.00"), doc.totalAmount);
        }

        @Test
        void multipleItemsMixedRates() {
            var vatItem = new ItemRequest("P1", null, "Con IVA 15%", "U",
                    BigDecimal.ONE, new BigDecimal("50.00"), BigDecimal.ZERO,
                    List.of(new TaxRequest("2", "4", new BigDecimal("15"))));

            var zeroItem = new ItemRequest("P2", null, "IVA 0%", "U",
                    BigDecimal.ONE, new BigDecimal("30.00"), BigDecimal.ZERO,
                    List.of(new TaxRequest("2", "0", BigDecimal.ZERO)));

            var doc = new Document();
            service.computeAndSetTotals(doc, List.of(vatItem, zeroItem));

            assertEquals(new BigDecimal("80.00"), doc.subtotalBeforeTax);
            assertEquals(new BigDecimal("50.00"), doc.subtotalVat15);
            assertEquals(new BigDecimal("30.00"), doc.subtotalVat0);
            assertEquals(new BigDecimal("7.50"), doc.vatAmount);
            assertEquals(new BigDecimal("87.50"), doc.totalAmount);
        }

        @Test
        void itemWithIce() {
            var iva = new TaxRequest("2", "4", new BigDecimal("15"));
            var ice = new TaxRequest("3", null, new BigDecimal("10"));
            var item = new ItemRequest("P1", null, "Bebida", "U",
                    BigDecimal.ONE, new BigDecimal("100.00"), BigDecimal.ZERO, List.of(iva, ice));

            var doc = new Document();
            service.computeAndSetTotals(doc, List.of(item));

            assertEquals(new BigDecimal("100.00"), doc.subtotalBeforeTax);
            assertEquals(new BigDecimal("15.00"), doc.vatAmount);
            assertEquals(new BigDecimal("10.00"), doc.iceAmount); // 100 * 10% = 10
            assertEquals(new BigDecimal("125.00"), doc.totalAmount); // 100 + 15 + 10
        }

        @Test
        void itemWithNoTaxes() {
            var item = new ItemRequest("P1", null, "Servicio", "U",
                    new BigDecimal("5"), new BigDecimal("20.00"), BigDecimal.ZERO, null);

            var doc = new Document();
            service.computeAndSetTotals(doc, List.of(item));

            assertEquals(new BigDecimal("100.00"), doc.subtotalBeforeTax);
            assertEquals(new BigDecimal("0.00"), doc.vatAmount);
            assertEquals(new BigDecimal("100.00"), doc.totalAmount);
        }

        @Test
        void nonTaxableAndExemptItems() {
            var nonTaxable = new ItemRequest("P1", null, "No objeto", "U",
                    BigDecimal.ONE, new BigDecimal("50.00"), BigDecimal.ZERO,
                    List.of(new TaxRequest("2", "6", BigDecimal.ZERO)));

            var exempt = new ItemRequest("P2", null, "Exento", "U",
                    BigDecimal.ONE, new BigDecimal("30.00"), BigDecimal.ZERO,
                    List.of(new TaxRequest("2", "7", BigDecimal.ZERO)));

            var doc = new Document();
            service.computeAndSetTotals(doc, List.of(nonTaxable, exempt));

            assertEquals(new BigDecimal("80.00"), doc.subtotalBeforeTax);
            assertEquals(new BigDecimal("50.00"), doc.subtotalNonTaxable);
            assertEquals(new BigDecimal("30.00"), doc.subtotalExempt);
            assertEquals(new BigDecimal("0.00"), doc.vatAmount);
            assertEquals(new BigDecimal("80.00"), doc.totalAmount);
        }
    }
}
