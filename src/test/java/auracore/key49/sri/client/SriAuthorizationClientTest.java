package auracore.key49.sri.client;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import auracore.key49.sri.SriException;

/**
 * Tests unitarios para SriAuthorizationClient.
 */
class SriAuthorizationClientTest {

    private static final String VALID_ACCESS_KEY = "0504202601179001691900110010020000000011234567813";

    // ── SOAP Envelope construction ──

    @Nested
    @DisplayName("construcción del sobre SOAP")
    class SoapEnvelopeConstruction {

        @Test
        @DisplayName("envelope contiene namespace SOAP")
        void envelopeContainsSoapNamespace() {
            var envelope = SriAuthorizationClient.buildSoapEnvelope(VALID_ACCESS_KEY);
            assertTrue(envelope.contains("http://schemas.xmlsoap.org/soap/envelope/"));
        }

        @Test
        @DisplayName("envelope contiene namespace de autorización SRI")
        void envelopeContainsAuthorizationNamespace() {
            var envelope = SriAuthorizationClient.buildSoapEnvelope(VALID_ACCESS_KEY);
            assertTrue(envelope.contains("http://ec.gob.sri.ws.autorizacion"));
        }

        @Test
        @DisplayName("envelope contiene operación autorizacionComprobante")
        void envelopeContainsOperation() {
            var envelope = SriAuthorizationClient.buildSoapEnvelope(VALID_ACCESS_KEY);
            assertTrue(envelope.contains("autorizacionComprobante"));
        }

        @Test
        @DisplayName("envelope contiene la clave de acceso")
        void envelopeContainsAccessKey() {
            var envelope = SriAuthorizationClient.buildSoapEnvelope(VALID_ACCESS_KEY);
            assertTrue(envelope.contains(VALID_ACCESS_KEY));
        }

        @Test
        @DisplayName("envelope contiene elemento claveAccesoComprobante")
        void envelopeContainsClaveAccesoElement() {
            var envelope = SriAuthorizationClient.buildSoapEnvelope(VALID_ACCESS_KEY);
            assertTrue(envelope.contains("<claveAccesoComprobante>"));
            assertTrue(envelope.contains("</claveAccesoComprobante>"));
        }

        @Test
        @DisplayName("envelope tiene estructura XML válida con Header y Body")
        void envelopeHasValidStructure() {
            var envelope = SriAuthorizationClient.buildSoapEnvelope(VALID_ACCESS_KEY);
            assertTrue(envelope.contains("soapenv:Header"));
            assertTrue(envelope.contains("soapenv:Body"));
        }
    }

    // ── Input validation ──

    @Nested
    @DisplayName("validación de inputs")
    class InputValidation {

        @Test
        @DisplayName("clave de acceso nula lanza excepción")
        void nullAccessKeyThrows() {
            var client = new SriAuthorizationClient();
            assertThrows(SriException.class,
                    () -> client.authorize(null, auracore.key49.core.model.enums.SriEnvironment.TEST));
        }

        @Test
        @DisplayName("clave de acceso vacía lanza excepción")
        void emptyAccessKeyThrows() {
            var client = new SriAuthorizationClient();
            assertThrows(SriException.class,
                    () -> client.authorize("", auracore.key49.core.model.enums.SriEnvironment.TEST));
        }

        @Test
        @DisplayName("clave de acceso con longitud incorrecta lanza excepción")
        void wrongLengthAccessKeyThrows() {
            var client = new SriAuthorizationClient();
            var ex = assertThrows(SriException.class,
                    () -> client.authorize("12345", auracore.key49.core.model.enums.SriEnvironment.TEST));
            assertTrue(ex.getMessage().contains("49 digits"));
        }

        @Test
        @DisplayName("clave de acceso con 48 dígitos lanza excepción")
        void shortAccessKeyThrows() {
            var client = new SriAuthorizationClient();
            assertThrows(SriException.class,
                    () -> client.authorize("0".repeat(48), auracore.key49.core.model.enums.SriEnvironment.TEST));
        }

        @Test
        @DisplayName("clave de acceso con 50 dígitos lanza excepción")
        void longAccessKeyThrows() {
            var client = new SriAuthorizationClient();
            assertThrows(SriException.class,
                    () -> client.authorize("0".repeat(50), auracore.key49.core.model.enums.SriEnvironment.TEST));
        }

        @Test
        @DisplayName("environment nulo lanza excepción")
        void nullEnvironmentThrows() {
            var client = new SriAuthorizationClient();
            assertThrows(SriException.class,
                    () -> client.authorize(VALID_ACCESS_KEY, null));
        }
    }
}
