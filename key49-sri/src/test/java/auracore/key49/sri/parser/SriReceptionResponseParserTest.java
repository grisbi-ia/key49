package auracore.key49.sri.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import auracore.key49.sri.SriException;
import auracore.key49.sri.model.ReceptionStatus;

/**
 * Tests unitarios para SriReceptionResponseParser.
 */
class SriReceptionResponseParserTest {

    // ── SOAP responses simuladas ──

    private static final String SOAP_RECIBIDA = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ns2:validarComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.recepcion">
                  <RespuestaRecepcionComprobante>
                    <estado>RECIBIDA</estado>
                    <comprobantes>
                      <comprobante>
                        <claveAcceso>0504202601179001691900110010020000000011234567813</claveAcceso>
                        <mensajes/>
                      </comprobante>
                    </comprobantes>
                  </RespuestaRecepcionComprobante>
                </ns2:validarComprobanteResponse>
              </soap:Body>
            </soap:Envelope>
            """;

    private static final String SOAP_RECIBIDA_WITH_WARNING = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ns2:validarComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.recepcion">
                  <RespuestaRecepcionComprobante>
                    <estado>RECIBIDA</estado>
                    <comprobantes>
                      <comprobante>
                        <claveAcceso>0504202601179001691900110010020000000011234567813</claveAcceso>
                        <mensajes>
                          <mensaje>
                            <identificador>60</identificador>
                            <mensaje>CONTRIBUYENTE ESPECIAL</mensaje>
                            <informacionAdicional>El emisor es contribuyente especial</informacionAdicional>
                            <tipo>ADVERTENCIA</tipo>
                          </mensaje>
                        </mensajes>
                      </comprobante>
                    </comprobantes>
                  </RespuestaRecepcionComprobante>
                </ns2:validarComprobanteResponse>
              </soap:Body>
            </soap:Envelope>
            """;

    private static final String SOAP_DEVUELTA_BUSINESS_ERROR = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ns2:validarComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.recepcion">
                  <RespuestaRecepcionComprobante>
                    <estado>DEVUELTA</estado>
                    <comprobantes>
                      <comprobante>
                        <claveAcceso>0504202601179001691900110010020000000011234567813</claveAcceso>
                        <mensajes>
                          <mensaje>
                            <identificador>35</identificador>
                            <mensaje>DOCUMENTO PREVIAMENTE RECIBIDO</mensaje>
                            <tipo>ERROR</tipo>
                          </mensaje>
                        </mensajes>
                      </comprobante>
                    </comprobantes>
                  </RespuestaRecepcionComprobante>
                </ns2:validarComprobanteResponse>
              </soap:Body>
            </soap:Envelope>
            """;

    private static final String SOAP_DEVUELTA_STRUCTURE_ERROR = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ns2:validarComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.recepcion">
                  <RespuestaRecepcionComprobante>
                    <estado>DEVUELTA</estado>
                    <comprobantes>
                      <comprobante>
                        <claveAcceso>0504202601179001691900110010020000000011234567813</claveAcceso>
                        <mensajes>
                          <mensaje>
                            <identificador>52</identificador>
                            <mensaje>ERROR EN ESTRUCTURA DEL COMPROBANTE</mensaje>
                            <informacionAdicional>El campo infoFactura es obligatorio</informacionAdicional>
                            <tipo>ERROR</tipo>
                          </mensaje>
                        </mensajes>
                      </comprobante>
                    </comprobantes>
                  </RespuestaRecepcionComprobante>
                </ns2:validarComprobanteResponse>
              </soap:Body>
            </soap:Envelope>
            """;

    private static final String SOAP_DEVUELTA_MULTIPLE_ERRORS = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ns2:validarComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.recepcion">
                  <RespuestaRecepcionComprobante>
                    <estado>DEVUELTA</estado>
                    <comprobantes>
                      <comprobante>
                        <claveAcceso>0504202601179001691900110010020000000011234567813</claveAcceso>
                        <mensajes>
                          <mensaje>
                            <identificador>45</identificador>
                            <mensaje>FECHA FUERA DE RANGO</mensaje>
                            <tipo>ERROR</tipo>
                          </mensaje>
                          <mensaje>
                            <identificador>65</identificador>
                            <mensaje>FECHA POSTERIOR A LA ACTUAL</mensaje>
                            <tipo>ERROR</tipo>
                          </mensaje>
                        </mensajes>
                      </comprobante>
                    </comprobantes>
                  </RespuestaRecepcionComprobante>
                </ns2:validarComprobanteResponse>
              </soap:Body>
            </soap:Envelope>
            """;

    // ── Tests: parsing exitoso ──

    @Nested
    @DisplayName("parsing exitoso")
    class SuccessfulParsing {

        @Test
        @DisplayName("parsea respuesta RECIBIDA sin mensajes")
        void parsesRecibida() {
            var response = SriReceptionResponseParser.parse(SOAP_RECIBIDA);

            assertEquals(ReceptionStatus.RECIBIDA, response.status());
            assertTrue(response.isReceived());
            assertEquals("0504202601179001691900110010020000000011234567813", response.accessKey());
            assertTrue(response.messages().isEmpty());
        }

        @Test
        @DisplayName("parsea respuesta RECIBIDA con advertencia")
        void parsesRecibidaWithWarning() {
            var response = SriReceptionResponseParser.parse(SOAP_RECIBIDA_WITH_WARNING);

            assertTrue(response.isReceived());
            assertFalse(response.hasBusinessErrors());
            assertEquals(1, response.messages().size());

            var msg = response.messages().getFirst();
            assertEquals("60", msg.identifier());
            assertEquals("CONTRIBUYENTE ESPECIAL", msg.message());
            assertEquals("El emisor es contribuyente especial", msg.additionalInfo());
            assertEquals("ADVERTENCIA", msg.type());
        }

        @Test
        @DisplayName("parsea respuesta DEVUELTA con error de negocio")
        void parsesDevueltaBusinessError() {
            var response = SriReceptionResponseParser.parse(SOAP_DEVUELTA_BUSINESS_ERROR);

            assertEquals(ReceptionStatus.DEVUELTA, response.status());
            assertFalse(response.isReceived());
            assertTrue(response.hasBusinessErrors());

            var msg = response.messages().getFirst();
            assertEquals("35", msg.identifier());
            assertEquals("DOCUMENTO PREVIAMENTE RECIBIDO", msg.message());
            assertTrue(msg.isBusinessError());
        }

        @Test
        @DisplayName("parsea respuesta DEVUELTA con error de estructura")
        void parsesDevueltaStructureError() {
            var response = SriReceptionResponseParser.parse(SOAP_DEVUELTA_STRUCTURE_ERROR);

            assertFalse(response.isReceived());
            assertTrue(response.hasBusinessErrors());

            var msg = response.messages().getFirst();
            assertEquals("52", msg.identifier());
            assertNotNull(msg.additionalInfo());
        }

        @Test
        @DisplayName("parsea respuesta DEVUELTA con múltiples errores")
        void parsesMultipleErrors() {
            var response = SriReceptionResponseParser.parse(SOAP_DEVUELTA_MULTIPLE_ERRORS);

            assertFalse(response.isReceived());
            assertEquals(2, response.messages().size());

            assertEquals("45", response.messages().get(0).identifier());
            assertEquals("65", response.messages().get(1).identifier());
            assertTrue(response.messages().stream().allMatch(m -> m.isBusinessError()));
        }

        @Test
        @DisplayName("extrae clave de acceso correctamente")
        void extractsAccessKey() {
            var response = SriReceptionResponseParser.parse(SOAP_RECIBIDA);
            assertEquals("0504202601179001691900110010020000000011234567813", response.accessKey());
        }
    }

    // ── Tests: errores de parsing ──

    @Nested
    @DisplayName("errores de parsing")
    class ParsingErrors {

        @Test
        @DisplayName("respuesta nula lanza excepción")
        void nullResponseThrows() {
            assertThrows(SriException.class,
                    () -> SriReceptionResponseParser.parse(null));
        }

        @Test
        @DisplayName("respuesta vacía lanza excepción")
        void emptyResponseThrows() {
            assertThrows(SriException.class,
                    () -> SriReceptionResponseParser.parse(""));
        }

        @Test
        @DisplayName("respuesta en blanco lanza excepción")
        void blankResponseThrows() {
            assertThrows(SriException.class,
                    () -> SriReceptionResponseParser.parse("   "));
        }

        @Test
        @DisplayName("XML inválido lanza excepción")
        void invalidXmlThrows() {
            assertThrows(SriException.class,
                    () -> SriReceptionResponseParser.parse("<not-closed"));
        }

        @Test
        @DisplayName("XML válido sin estado lanza excepción")
        void missingEstadoThrows() {
            var xml = """
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                      <soap:Body>
                        <RespuestaRecepcionComprobante>
                          <comprobantes/>
                        </RespuestaRecepcionComprobante>
                      </soap:Body>
                    </soap:Envelope>
                    """;
            assertThrows(SriException.class,
                    () -> SriReceptionResponseParser.parse(xml));
        }

        @Test
        @DisplayName("estado desconocido lanza excepción")
        void unknownEstadoThrows() {
            var xml = """
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                      <soap:Body>
                        <RespuestaRecepcionComprobante>
                          <estado>DESCONOCIDO</estado>
                        </RespuestaRecepcionComprobante>
                      </soap:Body>
                    </soap:Envelope>
                    """;
            assertThrows(SriException.class,
                    () -> SriReceptionResponseParser.parse(xml));
        }
    }
}
