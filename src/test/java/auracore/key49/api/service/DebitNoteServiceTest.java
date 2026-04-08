package auracore.key49.api.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import auracore.key49.api.dto.CreateDebitNoteRequest;
import auracore.key49.api.dto.CreateDebitNoteRequest.PaymentRequest;
import auracore.key49.api.dto.CreateDebitNoteRequest.ReasonRequest;
import auracore.key49.api.dto.CreateDebitNoteRequest.RecipientRequest;
import auracore.key49.api.dto.CreateDebitNoteRequest.TaxRequest;
import auracore.key49.api.exception.BusinessException;
import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.Document;

/**
 * Tests unitarios para DebitNoteService: validación y cálculo de totales.
 */
class DebitNoteServiceTest {

    DebitNoteService service;

    @BeforeEach
    void setUp() {
        service = new DebitNoteService();
        var om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        try {
            var omField = DebitNoteService.class.getDeclaredField("objectMapper");
            omField.setAccessible(true);
            omField.set(service, om);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Helpers ──
    private RecipientRequest validRecipient() {
        return new RecipientRequest("04", "1790016919001", "Empresa S.A.", "test@test.com", "0991234567");
    }

    private ReasonRequest validReason() {
        return new ReasonRequest("Intereses por mora en pago", new BigDecimal("50.00"));
    }

    private TaxRequest validTax() {
        return new TaxRequest("2", "4", new BigDecimal("15"));
    }

    private TaxRequest taxVat0() {
        return new TaxRequest("2", "0", BigDecimal.ZERO);
    }

    private PaymentRequest validPayment() {
        return new PaymentRequest("01", new BigDecimal("57.50"), 30, "dias");
    }

    private CreateDebitNoteRequest validRequest() {
        return new CreateDebitNoteRequest(
                "001", "001", "000000042",
                LocalDate.now(Key49Constants.EC_ZONE),
                validRecipient(),
                "01",
                "001-001-000000001",
                LocalDate.now(Key49Constants.EC_ZONE).minusDays(5),
                List.of(validReason()),
                List.of(validTax()),
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
            var req = new CreateDebitNoteRequest(
                    "01", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    List.of(validReason()), List.of(validTax()), null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "establishment".equals(d.field())));
        }

        @Test
        void invalidIssuePoint_fails() {
            var req = new CreateDebitNoteRequest(
                    "001", "1", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    List.of(validReason()), List.of(validTax()), null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "issue_point".equals(d.field())));
        }

        @Test
        void invalidSequenceNumber_fails() {
            var req = new CreateDebitNoteRequest(
                    "001", "001", "42",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    List.of(validReason()), List.of(validTax()), null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "sequence_number".equals(d.field())));
        }

        @Test
        void wrongIssueDate_fails() {
            var req = new CreateDebitNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.of(2020, 1, 1),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.of(2020, 1, 1).minusDays(1),
                    List.of(validReason()), List.of(validTax()), null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "issue_date".equals(d.field())));
        }

        @Test
        void nullRecipient_fails() {
            var req = new CreateDebitNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    null, "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    List.of(validReason()), List.of(validTax()), null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "recipient".equals(d.field())));
        }

        @Test
        void invalidRecipientIdType_fails() {
            var recipient = new RecipientRequest("99", "1234567890", "Test", null, null);
            var req = new CreateDebitNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    recipient, "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    List.of(validReason()), List.of(validTax()), null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> d.field().contains("recipient.id_type")));
        }

        @Test
        void missingModifiedDocumentCode_fails() {
            var req = new CreateDebitNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), null, "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    List.of(validReason()), List.of(validTax()), null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "modified_document_code".equals(d.field())));
        }

        @Test
        void invalidModifiedDocumentCode_fails() {
            var req = new CreateDebitNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "99", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    List.of(validReason()), List.of(validTax()), null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "modified_document_code".equals(d.field())));
        }

        @Test
        void missingModifiedDocumentNumber_fails() {
            var req = new CreateDebitNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", null,
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    List.of(validReason()), List.of(validTax()), null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "modified_document_number".equals(d.field())));
        }

        @Test
        void invalidModifiedDocumentNumberFormat_fails() {
            var req = new CreateDebitNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001001000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    List.of(validReason()), List.of(validTax()), null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "modified_document_number".equals(d.field())));
        }

        @Test
        void missingModifiedDocumentDate_fails() {
            var req = new CreateDebitNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    null,
                    List.of(validReason()), List.of(validTax()), null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "modified_document_date".equals(d.field())));
        }

        @Test
        void emptyReasons_fails() {
            var req = new CreateDebitNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    List.of(), List.of(validTax()), null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "reasons".equals(d.field())));
        }

        @Test
        void reasonWithoutDescription_fails() {
            var reason = new ReasonRequest(null, new BigDecimal("50.00"));
            var req = new CreateDebitNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    List.of(reason), List.of(validTax()), null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> d.field().contains("description")));
        }

        @Test
        void reasonWithZeroAmount_fails() {
            var reason = new ReasonRequest("Motivo", BigDecimal.ZERO);
            var req = new CreateDebitNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    List.of(reason), List.of(validTax()), null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> d.field().contains("amount")));
        }

        @Test
        void emptyTaxes_fails() {
            var req = new CreateDebitNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    List.of(validReason()), List.of(), null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "taxes".equals(d.field())));
        }

        @Test
        void invalidPaymentMethod_fails() {
            var payment = new PaymentRequest("99", new BigDecimal("50.00"), null, null);
            var req = new CreateDebitNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    List.of(validReason()), List.of(validTax()),
                    List.of(payment), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> d.field().contains("payment_method")));
        }

        @Test
        void multipleErrors_collected() {
            var req = new CreateDebitNoteRequest(
                    "01", "1", "42",
                    LocalDate.of(2020, 1, 1),
                    null, null, null, null, null, null, null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertEquals(400, ex.httpStatus());
            assertTrue(ex.details().size() >= 4, "Should collect multiple validation errors");
        }
    }

    // ── Totals calculation tests ──
    @Nested
    class TotalsCalculation {

        @Test
        void singleReasonWithVat15_calculatesCorrectly() {
            var doc = new Document();
            var request = validRequest();

            service.computeAndSetTotals(doc, request);

            assertEquals(new BigDecimal("50.00"), doc.subtotalBeforeTax);
            assertEquals(new BigDecimal("7.50"), doc.vatAmount);
            assertEquals(new BigDecimal("57.50"), doc.totalAmount);
        }

        @Test
        void multipleReasons_calculatesSubtotal() {
            var doc = new Document();
            var reasons = List.of(
                    new ReasonRequest("Motivo 1", new BigDecimal("30.00")),
                    new ReasonRequest("Motivo 2", new BigDecimal("20.00"))
            );
            var request = new CreateDebitNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    reasons, List.of(validTax()), null, null);

            service.computeAndSetTotals(doc, request);

            assertEquals(new BigDecimal("50.00"), doc.subtotalBeforeTax);
            assertEquals(new BigDecimal("7.50"), doc.vatAmount);
            assertEquals(new BigDecimal("57.50"), doc.totalAmount);
        }

        @Test
        void vatZero_noTaxAdded() {
            var doc = new Document();
            var request = new CreateDebitNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    List.of(validReason()), List.of(taxVat0()), null, null);

            service.computeAndSetTotals(doc, request);

            assertEquals(new BigDecimal("50.00"), doc.subtotalBeforeTax);
            assertEquals(new BigDecimal("0.00"), doc.vatAmount);
            assertEquals(new BigDecimal("50.00"), doc.totalAmount);
        }

        @Test
        void noDiscount_setToZero() {
            var doc = new Document();
            service.computeAndSetTotals(doc, validRequest());

            assertEquals(new BigDecimal("0.00"), doc.totalDiscount);
        }
    }
}

                                     
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                               
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                               
                
                
                
                
                                                               
                
                
                
                
                                                      
                
                
                
                
                                                               
                
                
                
                
                
                                                      
                
                
                                                                                                                                     
                
                
                 
                
                
                
                
                                                                              
                
                
                
                
                                                                                         