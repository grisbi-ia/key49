package auracore.key49.sri.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SriMessageTest {

    @Test
    @DisplayName("código 43 → clave de acceso ya registrada, no es error de negocio")
    void shouldDetectAlreadyRegistered() {
        var msg = new SriMessage("43", "CLAVE ACCESO REGISTRADA", null, "ERROR");
        assertTrue(msg.isAlreadyRegistered());
        assertFalse(msg.isBusinessError());
        assertFalse(msg.isInProcessing());
    }

    @Test
    @DisplayName("código 70 → en procesamiento")
    void shouldDetectInProcessing() {
        var msg = new SriMessage("70", "CLAVE DE ACCESO EN PROCESAMIENTO", null, "ERROR");
        assertTrue(msg.isInProcessing());
        assertFalse(msg.isAlreadyRegistered());
        assertFalse(msg.isBusinessError());
    }

    @Test
    @DisplayName("código 45 → error de negocio")
    void shouldDetectBusinessError() {
        var msg = new SriMessage("45", "FECHA FUERA DE RANGO", null, "ERROR");
        assertTrue(msg.isBusinessError());
        assertFalse(msg.isAlreadyRegistered());
    }

    @Test
    @DisplayName("código 43 con type distinto de ERROR no se considera registrado")
    void shouldIgnoreNonErrorType() {
        var msg = new SriMessage("43", "CLAVE ACCESO REGISTRADA", null, "ADVERTENCIA");
        assertFalse(msg.isAlreadyRegistered());
        assertFalse(msg.isInProcessing());
    }
}
