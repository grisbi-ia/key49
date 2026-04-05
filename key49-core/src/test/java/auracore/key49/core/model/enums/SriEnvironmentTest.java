package auracore.key49.core.model.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class SriEnvironmentTest {

    @ParameterizedTest
    @CsvSource({"1, TEST", "2, PRODUCTION"})
    void shouldResolveFromSriCode(String code, SriEnvironment expected) {
        assertEquals(expected, SriEnvironment.fromSriCode(code));
    }

    @Test
    void shouldReturnCorrectSriCode() {
        assertEquals("1", SriEnvironment.TEST.sriCode());
        assertEquals("2", SriEnvironment.PRODUCTION.sriCode());
    }

    @Test
    void shouldThrowForUnknownCode() {
        assertThrows(IllegalArgumentException.class, () -> SriEnvironment.fromSriCode("3"));
    }
}
