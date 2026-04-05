package auracore.key49.core.model.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class VatRateTest {

    @ParameterizedTest
    @CsvSource({"0, ZERO", "2, TWELVE", "3, FOURTEEN", "4, FIFTEEN", "6, NOT_TAXABLE", "7, EXEMPT", "8, DIFFERENTIATED"})
    void shouldResolveFromSriCode(String code, VatRate expected) {
        assertEquals(expected, VatRate.fromSriCode(code));
    }

    @Test
    void shouldReturnCorrectPercentage() {
        assertEquals(BigDecimal.ZERO, VatRate.ZERO.percentage());
        assertEquals(new BigDecimal("12"), VatRate.TWELVE.percentage());
        assertEquals(new BigDecimal("15"), VatRate.FIFTEEN.percentage());
    }

    @Test
    void shouldThrowForUnknownCode() {
        assertThrows(IllegalArgumentException.class, () -> VatRate.fromSriCode("99"));
    }
}
