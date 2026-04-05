package auracore.key49.core.model.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentMethodTest {

    @ParameterizedTest
    @CsvSource({
            "01, SIN_UTILIZACION_DEL_SISTEMA_FINANCIERO",
            "15, COMPENSACION_DE_DEUDAS",
            "16, TARJETA_DE_DEBITO",
            "17, DINERO_ELECTRONICO",
            "18, TARJETA_PREPAGO",
            "19, TARJETA_DE_CREDITO",
            "20, OTROS_CON_UTILIZACION_SISTEMA_FINANCIERO",
            "21, ENDOSO_DE_TITULOS"
    })
    void shouldResolveFromSriCode(String code, PaymentMethod expected) {
        assertEquals(expected, PaymentMethod.fromSriCode(code));
    }

    @Test
    void shouldReturnCorrectSriCode() {
        assertEquals("01", PaymentMethod.SIN_UTILIZACION_DEL_SISTEMA_FINANCIERO.sriCode());
        assertEquals("19", PaymentMethod.TARJETA_DE_CREDITO.sriCode());
    }

    @Test
    void shouldThrowForUnknownCode() {
        assertThrows(IllegalArgumentException.class, () -> PaymentMethod.fromSriCode("99"));
    }
}
