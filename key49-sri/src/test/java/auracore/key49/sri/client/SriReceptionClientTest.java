package auracore.key49.sri.client;

import java.util.Base64;

import auracore.key49.sri.SriException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para SriReceptionClient.
 */
class SriReceptionClientTest {

    private static final String SAMPLE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <factura id="comprobante" version="2.1.0">
              <infoTributaria>
                <razonSocial>EMPRESA DEMO</razonSocial>
              </infoTributaria>
            </factura>
            """;

    // ── SOAP Envelope construction ──

    @Nested
    @DisplayName("construcción del sobre SOAP")
    class SoapEnvelopeConstruction {

        @Test
        @DisplayName("envelope contiene namespace SOAP")
        void envelopeContainsSoapNamespace() {
            var envelope = SriReceptionClient.buildSoapEnvelope("dGVzdA==");
            assertTrue(envelope.contains("http://schemas.xmlsoap.org/soap/envelope/"));
        }

        @Test
        @DisplayName("envelope contiene namespace de recepción SRI")
        void envelopeContainsReceptionNamespace() {
            var envelope = SriReceptionClient.buildSoapEnvelope("dGVzdA==");
            assertTrue(envelope.contains("http://ec.gob.sri.ws.recepcion"));
        }

        @Test
        @DisplayName("envelope contiene operación validarComprobante")
        void envelopeContainsOperation() {
            var envelope = SriReceptionClient.buildSoapEnvelope("dGVzdA==");
            assertTrue(envelope.contains("validarComprobante"));
        }

        @Test
        @DisplayName("envelope contiene el XML en Base64")
        void envelopeContainsBase64() {
            var base64 = Base64.getEncoder().encodeToString(SAMPLE_XML.getBytes());
            var envelope = SriReceptionClient.buildSoapEnvelope(base64);
            assertTrue(envelope.contains(base64));
        }

        @Test
        @DisplayName("envelope tiene estructura XML válida con Header y Body")
        void envelopeHasValidStructure() {
            var envelope = SriReceptionClient.buildSoapEnvelope("dGVzdA==");
            assertTrue(envelope.contains("soapenv:Header"));
            assertTrue(envelope.contains("soapenv:Body"));
            assertTrue(envelope.contains("<xml>"));
            assertTrue(envelope.contains("</xml>"));
        }
    }

    // ── Input validation ──

    @Nested
    @DisplayName("validación de inputs")
    class InputValidation {

        @Test
        @DisplayName("XML nulo lanza excepción")
        void nullXmlThrows() {
            var client = new SriReceptionClient();
            assertThrows(SriException.class,
                    () -> client.send(null, auracore.key49.core.model.enums.SriEnvironment.TEST));
        }

        @Test
        @DisplayName("XML vacío lanza excepción")
        void emptyXmlThrows() {
            var client = new SriReceptionClient();
            assertThrows(SriException.class,
                    () -> client.send("", auracore.key49.core.model.enums.SriEnvironment.TEST));
        }

        @Test
        @DisplayName("environment nulo lanza excepción")
        void nullEnvironmentThrows() {
            var client = new SriReceptionClient();
            assertThrows(SriException.class,
                    () -> client.send(SAMPLE_XML, null));
        }
    }
}
