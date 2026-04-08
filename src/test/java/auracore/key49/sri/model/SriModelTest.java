package auracore.key49.sri.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

    // ── AuthorizationStatus tests ──

    @Nested
    @DisplayName("AuthorizationStatus")
    class AuthorizationStatusTest {

        @Test
        @DisplayName("parsea AUTORIZADO desde string")
        void parseAutorizado() {
            assertEquals(AuthorizationStatus.AUTORIZADO, AuthorizationStatus.fromValue("AUTORIZADO"));
        }

        @Test
        @DisplayName("parsea NO AUTORIZADO desde string")
        void parseNoAutorizado() {
            assertEquals(AuthorizationStatus.NO_AUTORIZADO, AuthorizationStatus.fromValue("NO AUTORIZADO"));
        }

        @Test
        @DisplayName("parsea case-insensitive")
        void parseCaseInsensitive() {
            assertEquals(AuthorizationStatus.AUTORIZADO, AuthorizationStatus.fromValue("autorizado"));
            assertEquals(AuthorizationStatus.NO_AUTORIZADO, AuthorizationStatus.fromValue("no autorizado"));
        }

        @Test
        @DisplayName("valor desconocido lanza excepción")
        void unknownValueThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> AuthorizationStatus.fromValue("INVALIDO"));
        }

        @Test
        @DisplayName("value() retorna el string original")
        void valueReturnsString() {
            assertEquals("AUTORIZADO", AuthorizationStatus.AUTORIZADO.value());
            assertEquals("NO AUTORIZADO", AuthorizationStatus.NO_AUTORIZADO.value());
        }
    }

    // ── SriAuthorizationResponse tests ──

    @Nested
    @DisplayName("SriAuthorizationResponse")
    class SriAuthorizationResponseTest {

        @Test
        @DisplayName("respuesta AUTORIZADO con datos completos")
        void authorizedWithFullData() {
            var response = new SriAuthorizationResponse(
                    AuthorizationStatus.AUTORIZADO,
                    "0504202601179001691900110010020000000011234567813",
                    "05/04/2026 10:30:45",
                    "0504202601179001691900110010020000000011234567813",
                    "<factura/>",
                    null);
            assertTrue(response.isAuthorized());
            assertFalse(response.hasBusinessErrors());
            assertTrue(response.messages().isEmpty());
        }

        @Test
        @DisplayName("respuesta NO AUTORIZADO con error de negocio")
        void notAuthorizedWithBusinessError() {
            var msg = new SriMessage("35", "DOCUMENTO PREVIAMENTE RECIBIDO", null, "ERROR");
            var response = new SriAuthorizationResponse(
                    AuthorizationStatus.NO_AUTORIZADO,
                    null, null,
                    "0504202601179001691900110010020000000011234567813",
                    "<factura/>",
                    java.util.List.of(msg));
            assertFalse(response.isAuthorized());
            assertTrue(response.hasBusinessErrors());
            assertEquals(1, response.messages().size());
        }

        @Test
        @DisplayName("respuesta NO AUTORIZADO sin número de autorización")
        void notAuthorizedNullAuthNumber() {
            var response = new SriAuthorizationResponse(
                    AuthorizationStatus.NO_AUTORIZADO,
                    null, null,
                    "0504202601179001691900110010020000000011234567813",
                    null, null);
            assertNull(response.authorizationNumber());
            assertNull(response.authorizationDate());
            assertNull(response.authorizedXml());
        }

        @Test
        @DisplayName("messages es lista inmutable")
        void messagesAreImmutable() {
            var msg = new SriMessage("10", "Test", null, "ERROR");
            var response = new SriAuthorizationResponse(
                    AuthorizationStatus.AUTORIZADO,
                    "123", "05/04/2026", "key", "<xml/>",
                    java.util.List.of(msg));
            assertThrows(UnsupportedOperationException.class,
                    () -> response.messages().add(new SriMessage("20", "X", null, "ERROR")));
        }

        @Test
        @DisplayName("respuesta AUTORIZADO con advertencia no tiene errores de negocio")
        void authorizedWithWarningNoBusinessErrors() {
            var msg = new SriMessage("60", "Advertencia", null, "ADVERTENCIA");
            var response = new SriAuthorizationResponse(
                    AuthorizationStatus.AUTORIZADO,
                    "123", "05/04/2026", "key", "<xml/>",
                    java.util.List.of(msg));
            assertTrue(response.isAuthorized());
            assertFalse(response.hasBusinessErrors());
        }
    }
}
