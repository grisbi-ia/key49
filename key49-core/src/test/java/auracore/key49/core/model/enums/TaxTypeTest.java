package auracore.key49.core.model.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class TaxTypeTest {

    @ParameterizedTest
    @CsvSource({"2, IVA", "3, ICE", "5, IRBPNR"})
    void shouldResolveFromSriCode(String code, TaxType expected) {
        assertEquals(expected, TaxType.fromSriCode(code));
    }

    @Test
    void shouldReturnCorrectSriCode() {
        assertEquals("2", TaxType.IVA.sriCode());
    }

    @Test
    void shouldThrowForUnknownCode() {
        assertThrows(IllegalArgumentException.class, () -> TaxType.fromSriCode("9"));
    }
}
