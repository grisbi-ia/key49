package auracore.key49.sri.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para los modelos de respuesta del SRI.
 */
class SriModelTest {

    @Nested
    @DisplayName("ReceptionStatus")
    class ReceptionStatusTest {

        @Test
        @DisplayName("parsea RECIBIDA desde string")
        void parseRecibida() {
            assertEquals(ReceptionStatus.RECIBIDA, ReceptionStatus.fromValue("RECIBIDA"));
        }

        @Test
        @DisplayName("parsea DEVUELTA desde string")
        void parseDevuelta() {
            assertEquals(ReceptionStatus.DEVUELTA, ReceptionStatus.fromValue("DEVUELTA"));
        }

        @Test
        @DisplayName("parsea case-insensitive")
        void parseCaseInsensitive() {
            assertEquals(ReceptionStatus.RECIBIDA, ReceptionStatus.fromValue("recibida"));
            assertEquals(ReceptionStatus.DEVUELTA, ReceptionStatus.fromValue("Devuelta"));
        }

        @Test
        @DisplayName("valor desconocido lanza excepción")
        void unknownValueThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> ReceptionStatus.fromValue("INVALIDO"));
        }

        @Test
        @DisplayName("value() retorna el string original")
        void valueReturnsString() {
            assertEquals("RECIBIDA", ReceptionStatus.RECIBIDA.value());
            assertEquals("DEVUELTA", ReceptionStatus.DEVUELTA.value());
        }
    }

    @Nested
    @DisplayName("SriMessage")
    class SriMessageTest {

        @Test
        @DisplayName("error de negocio código 35 (ya registrado)")
        void businessError35() {
            var msg = new SriMessage("35", "DOCUMENTO PREVIAMENTE RECIBIDO", null, "ERROR");
            assertTrue(msg.isBusinessError());
        }

        @Test
        @DisplayName("error de negocio código 45 (fecha fuera de rango)")
        void businessError45() {
            var msg = new SriMessage("45", "FECHA FUERA DE RANGO", null, "ERROR");
            assertTrue(msg.isBusinessError());
        }

        @Test
        @DisplayName("error de negocio código 52 (estructura inválida)")
        void businessError52() {
            var msg = new SriMessage("52", "ERROR EN ESTRUCTURA", null, "ERROR");
            assertTrue(msg.isBusinessError());
        }

        @Test
        @DisplayName("error de negocio código 65 (fecha futura)")
        void businessError65() {
            var msg = new SriMessage("65", "FECHA POSTERIOR A LA ACTUAL", null, "ERROR");
            assertTrue(msg.isBusinessError());
        }

        @Test
        @DisplayName("advertencia no es error de negocio")
        void warningIsNotBusinessError() {
            var msg = new SriMessage("35", "Advertencia", null, "ADVERTENCIA");
            assertFalse(msg.isBusinessError());
        }

        @Test
        @DisplayName("error con código desconocido no es error de negocio")
        void unknownCodeIsNotBusinessError() {
            var msg = new SriMessage("99", "Error desconocido", null, "ERROR");
            assertFalse(msg.isBusinessError());
        }

        @Test
        @DisplayName("mensaje informativo no es error de negocio")
        void informativeIsNotBusinessError() {
            var msg = new SriMessage("35", "Info", null, "INFORMATIVO");
            assertFalse(msg.isBusinessError());
        }
    }

    @Nested
    @DisplayName("SriReceptionResponse")
    class SriReceptionResponseTest {

        @Test
        @DisplayName("respuesta RECIBIDA sin mensajes")
        void receivedWithoutMessages() {
            var response = new SriReceptionResponse(
                    ReceptionStatus.RECIBIDA, "4905202601179001691900110010020000000011234567813", null);
            assertTrue(response.isReceived());
            assertFalse(response.hasBusinessErrors());
            assertTrue(response.messages().isEmpty());
        }

        @Test
        @DisplayName("respuesta DEVUELTA con error de negocio")
        void rejectedWithBusinessError() {
            var msg = new SriMessage("35", "DOCUMENTO PREVIAMENTE RECIBIDO", null, "ERROR");
            var response = new SriReceptionResponse(
                    ReceptionStatus.DEVUELTA, null, java.util.List.of(msg));
            assertFalse(response.isReceived());
            assertTrue(response.hasBusinessErrors());
            assertEquals(1, response.messages().size());
        }

        @Test
        @DisplayName("respuesta RECIBIDA con advertencia")
        void receivedWithWarning() {
            var msg = new SriMessage("10", "Advertencia leve", null, "ADVERTENCIA");
            var response = new SriReceptionResponse(
                    ReceptionStatus.RECIBIDA, "4905202601179001691900110010020000000011234567813",
                    java.util.List.of(msg));
            assertTrue(response.isReceived());
            assertFalse(response.hasBusinessErrors());
        }

        @Test
        @DisplayName("messages es lista inmutable")
        void messagesAreImmutable() {
            var msg = new SriMessage("10", "Test", null, "ERROR");
            var response = new SriReceptionResponse(
                    ReceptionStatus.RECIBIDA, null, java.util.List.of(msg));
            assertThrows(UnsupportedOperationException.class,
                    () -> response.messages().add(new SriMessage("20", "X", null, "ERROR")));
        }
    }
}
