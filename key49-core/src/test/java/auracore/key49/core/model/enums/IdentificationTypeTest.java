package auracore.key49.core.model.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class IdentificationTypeTest {

    @ParameterizedTest
    @CsvSource({"04, RUC", "05, CEDULA", "06, PASSPORT", "07, FINAL_CONSUMER"})
    void shouldResolveFromSriCode(String code, IdentificationType expected) {
        assertEquals(expected, IdentificationType.fromSriCode(code));
    }

    @Test
    void shouldReturnCorrectLength() {
        assertEquals(13, IdentificationType.RUC.length());
        assertEquals(10, IdentificationType.CEDULA.length());
        assertEquals(-1, IdentificationType.PASSPORT.length());
        assertEquals(13, IdentificationType.FINAL_CONSUMER.length());
    }

    @Test
    void shouldThrowForUnknownCode() {
        assertThrows(IllegalArgumentException.class, () -> IdentificationType.fromSriCode("99"));
    }
}
