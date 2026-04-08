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

import auracore.key49.api.dto.CreateCreditNoteRequest;
import auracore.key49.api.dto.CreateCreditNoteRequest.ItemRequest;
import auracore.key49.api.dto.CreateCreditNoteRequest.RecipientRequest;
import auracore.key49.api.dto.CreateCreditNoteRequest.TaxRequest;
import auracore.key49.api.exception.BusinessException;
import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.Document;

/**
 * Tests unitarios para CreditNoteService: validación y cálculo de totales.
 */
class CreditNoteServiceTest {

    CreditNoteService service;

    @BeforeEach
    void setUp() {
        service = new CreditNoteService();
        var om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        try {
            var omField = CreditNoteService.class.getDeclaredField("objectMapper");
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

    private RecipientRequest recipientWithCedula() {
        return new RecipientRequest("05", "1710034065", "Juan Pérez", "juan@test.com", null);
    }

    private ItemRequest validItem() {
        return new ItemRequest(
                "PROD-001", null, "Servicio de hosting",
                BigDecimal.ONE, new BigDecimal("50.00"), BigDecimal.ZERO,
                List.of(new TaxRequest("2", "4", new BigDecimal("15"))));
    }

    private ItemRequest itemWithVat0() {
        return new ItemRequest(
                "PROD-002", null, "Producto exento",
                new BigDecimal("2"), new BigDecimal("100.00"), BigDecimal.ZERO,
                List.of(new TaxRequest("2", "0", BigDecimal.ZERO)));
    }

    private CreateCreditNoteRequest validRequest() {
        return new CreateCreditNoteRequest(
                "001", "001", "000000042",
                LocalDate.now(Key49Constants.EC_ZONE),
                validRecipient(),
                "01",
                "001-001-000000001",
                LocalDate.now(Key49Constants.EC_ZONE).minusDays(5),
                "Devolución de producto",
                List.of(validItem()),
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
            var req = new CreateCreditNoteRequest(
                    "01", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    "Motivo", List.of(validItem()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "establishment".equals(d.field())));
        }

        @Test
        void invalidIssuePoint_fails() {
            var req = new CreateCreditNoteRequest(
                    "001", "1", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    "Motivo", List.of(validItem()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "issue_point".equals(d.field())));
        }

        @Test
        void invalidSequenceNumber_fails() {
            var req = new CreateCreditNoteRequest(
                    "001", "001", "42",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    "Motivo", List.of(validItem()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "sequence_number".equals(d.field())));
        }

        @Test
        void wrongIssueDate_fails() {
            var req = new CreateCreditNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.of(2020, 1, 1),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.of(2020, 1, 1).minusDays(1),
                    "Motivo", List.of(validItem()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "issue_date".equals(d.field())));
        }

        @Test
        void nullRecipient_fails() {
            var req = new CreateCreditNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    null, "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    "Motivo", List.of(validItem()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "recipient".equals(d.field())));
        }

        @Test
        void invalidRecipientIdType_fails() {
            var recipient = new RecipientRequest("99", "1234567890", "Test", null, null);
            var req = new CreateCreditNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    recipient, "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    "Motivo", List.of(validItem()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> d.field().contains("recipient.id_type")));
        }

        @Test
        void missingModifiedDocumentCode_fails() {
            var req = new CreateCreditNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), null, "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    "Motivo", List.of(validItem()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "modified_document_code".equals(d.field())));
        }

        @Test
        void invalidModifiedDocumentCode_fails() {
            var req = new CreateCreditNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "99", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    "Motivo", List.of(validItem()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "modified_document_code".equals(d.field())));
        }

        @Test
        void missingModifiedDocumentNumber_fails() {
            var req = new CreateCreditNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", null,
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    "Motivo", List.of(validItem()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "modified_document_number".equals(d.field())));
        }

        @Test
        void invalidModifiedDocumentNumberFormat_fails() {
            var req = new CreateCreditNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001001000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    "Motivo", List.of(validItem()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "modified_document_number".equals(d.field())));
        }

        @Test
        void missingModifiedDocumentDate_fails() {
            var req = new CreateCreditNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    null,
                    "Motivo", List.of(validItem()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "modified_document_date".equals(d.field())));
        }

        @Test
        void missingReason_fails() {
            var req = new CreateCreditNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    null, List.of(validItem()), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "reason".equals(d.field())));
        }

        @Test
        void emptyItems_fails() {
            var req = new CreateCreditNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    "Motivo", List.of(), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> "items".equals(d.field())));
        }

        @Test
        void itemWithoutDescription_fails() {
            var item = new ItemRequest("P1", null, null,
                    BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, null);
            var req = new CreateCreditNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    "Motivo", List.of(item), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> d.field().contains("description")));
        }

        @Test
        void itemWithZeroQuantity_fails() {
            var item = new ItemRequest("P1", null, "Test",
                    BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO, null);
            var req = new CreateCreditNoteRequest(
                    "001", "001", "000000042",
                    LocalDate.now(Key49Constants.EC_ZONE),
                    validRecipient(), "01", "001-001-000000001",
                    LocalDate.now(Key49Constants.EC_ZONE).minusDays(1),
                    "Motivo", List.of(item), null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertTrue(ex.details().stream().anyMatch(d -> d.field().contains("quantity")));
        }

        @Test
        void multipleErrors_collected() {
            var req = new CreateCreditNoteRequest(
                    "01", "1", "42",
                    LocalDate.of(2020, 1, 1),
                    null, null, null, null, null, null, null);

            var ex = assertThrows(BusinessException.class, () -> service.validateCreateRequest(req));
            assertEquals(400, ex.httpStatus());
            assertTrue(ex.details().size() >= 4, "Should collect multiple validation errors");
        }
    }

    // ── Totals calculation tests ──
    @Nested
    class TotalsCalculation {

        @Test
        void singleItemWithVat15_calculatesCorrectly() {
            var doc = new Document();
            var items = List.of(validItem());

            service.computeAndSetTotals(doc, items);

            assertEquals(new BigDecimal("50.00"), doc.subtotalBeforeTax);
            assertEquals(new BigDecimal("7.50"), doc.vatAmount);
            assertEquals(new BigDecimal("57.50"), doc.totalAmount);
        }

        @Test
        void multipleItems_calculatesCorrectly() {
            var doc = new Document();
            var items = List.of(validItem(), itemWithVat0());

            service.computeAndSetTotals(doc, items);

            assertEquals(new BigDecimal("250.00"), doc.subtotalBeforeTax);
            assertEquals(new BigDecimal("7.50"), doc.vatAmount);
            assertEquals(new BigDecimal("257.50"), doc.totalAmount);
        }

        @Test
        void itemWithDiscount_appliedCorrectly() {
            var item = new ItemRequest(
                    "P1", null, "Discounted",
                    new BigDecimal("10"), new BigDecimal("20.00"), new BigDecimal("5.00"),
                    List.of(new TaxRequest("2", "4", new BigDecimal("15"))));

            var doc = new Document();
            service.computeAndSetTotals(doc, List.of(item));

            assertEquals(new BigDecimal("195.00"), doc.subtotalBeforeTax);
            assertEquals(new BigDecimal("5.00"), doc.totalDiscount);
        }
    }
}
                                     
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                               
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                      
                
                
                
                
                                                      
                         
                
                
                
                
                                                      
                         
                
                
                
                
                                                      
                
                
                                                                                                                                                                                          
                
                
                                                     