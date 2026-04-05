package auracore.key49.core.model.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class DocumentTypeTest {

    @ParameterizedTest
    @CsvSource({
            "01, INVOICE",
            "03, PURCHASE_CLEARANCE",
            "04, CREDIT_NOTE",
            "05, DEBIT_NOTE",
            "06, WAYBILL",
            "07, WITHHOLDING"
    })
    void shouldResolveFromSriCode(String code, DocumentType expected) {
        assertEquals(expected, DocumentType.fromSriCode(code));
    }

    @Test
    void shouldReturnCorrectSriCode() {
        assertEquals("01", DocumentType.INVOICE.sriCode());
        assertEquals("07", DocumentType.WITHHOLDING.sriCode());
    }

    @Test
    void shouldThrowForUnknownCode() {
        assertThrows(IllegalArgumentException.class, () -> DocumentType.fromSriCode("99"));
    }
}
