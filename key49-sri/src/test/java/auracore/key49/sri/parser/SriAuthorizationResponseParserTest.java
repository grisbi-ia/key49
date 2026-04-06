package auracore.key49.sri.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import auracore.key49.sri.SriException;
import auracore.key49.sri.model.AuthorizationStatus;

/**
 * Tests unitarios para SriAuthorizationResponseParser.
 */
class SriAuthorizationResponseParserTest {

    // ── SOAP responses simuladas ──

    private static final String ACCESS_KEY = "0504202601179001691900110010020000000011234567813";

    private static final String SOAP_AUTORIZADO = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ns2:autorizacionComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.autorizacion">
                  <RespuestaAutorizacionComprobante>
                    <claveAccesoConsultada>%s</claveAccesoConsultada>
                    <numeroComprobantes>1</numeroComprobantes>
                    <autorizaciones>
                      <autorizacion>
                        <estado>AUTORIZADO</estado>
                        <numeroAutorizacion>%s</numeroAutorizacion>
                        <fechaAutorizacion>05/04/2026 10:30:45</fechaAutorizacion>
                        <comprobante><![CDATA[<?xml version="1.0"?><factura id="comprobante" version="2.1.0"><infoTributaria><razonSocial>DEMO</razonSocial></infoTributaria></factura>]]></comprobante>
                        <mensajes/>
                      </autorizacion>
                    </autorizaciones>
                  </RespuestaAutorizacionComprobante>
                </ns2:autorizacionComprobanteResponse>
              </soap:Body>
            </soap:Envelope>
            """.formatted(ACCESS_KEY, ACCESS_KEY);

    private static final String SOAP_AUTORIZADO_WITH_WARNING = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ns2:autorizacionComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.autorizacion">
                  <RespuestaAutorizacionComprobante>
                    <claveAccesoConsultada>%s</claveAccesoConsultada>
                    <numeroComprobantes>1</numeroComprobantes>
                    <autorizaciones>
                      <autorizacion>
                        <estado>AUTORIZADO</estado>
                        <numeroAutorizacion>%s</numeroAutorizacion>
                        <fechaAutorizacion>05/04/2026 10:30:45</fechaAutorizacion>
                        <comprobante><![CDATA[<factura/>]]></comprobante>
                        <mensajes>
                          <mensaje>
                            <identificador>60</identificador>
                            <mensaje>CONTRIBUYENTE ESPECIAL</mensaje>
                            <informacionAdicional>El emisor es contribuyente especial</informacionAdicional>
                            <tipo>ADVERTENCIA</tipo>
                          </mensaje>
                        </mensajes>
                      </autorizacion>
                    </autorizaciones>
                  </RespuestaAutorizacionComprobante>
                </ns2:autorizacionComprobanteResponse>
              </soap:Body>
            </soap:Envelope>
            """.formatted(ACCESS_KEY, ACCESS_KEY);

    private static final String SOAP_NO_AUTORIZADO = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ns2:autorizacionComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.autorizacion">
                  <RespuestaAutorizacionComprobante>
                    <claveAccesoConsultada>%s</claveAccesoConsultada>
                    <numeroComprobantes>1</numeroComprobantes>
                    <autorizaciones>
                      <autorizacion>
                        <estado>NO AUTORIZADO</estado>
                        <comprobante><![CDATA[<factura/>]]></comprobante>
                        <mensajes>
                          <mensaje>
                            <identificador>35</identificador>
                            <mensaje>DOCUMENTO PREVIAMENTE RECIBIDO</mensaje>
                            <tipo>ERROR</tipo>
                          </mensaje>
                        </mensajes>
                      </autorizacion>
                    </autorizaciones>
                  </RespuestaAutorizacionComprobante>
                </ns2:autorizacionComprobanteResponse>
              </soap:Body>
            </soap:Envelope>
            """.formatted(ACCESS_KEY);

    private static final String SOAP_NO_AUTORIZADO_MULTIPLE_ERRORS = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ns2:autorizacionComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.autorizacion">
                  <RespuestaAutorizacionComprobante>
                    <claveAccesoConsultada>%s</claveAccesoConsultada>
                    <numeroComprobantes>1</numeroComprobantes>
                    <autorizaciones>
                      <autorizacion>
                        <estado>NO AUTORIZADO</estado>
                        <comprobante><![CDATA[<factura/>]]></comprobante>
                        <mensajes>
                          <mensaje>
                            <identificador>45</identificador>
                            <mensaje>FECHA FUERA DE RANGO</mensaje>
                            <tipo>ERROR</tipo>
                          </mensaje>
                          <mensaje>
                            <identificador>52</identificador>
                            <mensaje>ERROR EN ESTRUCTURA DEL COMPROBANTE</mensaje>
                            <informacionAdicional>Campo obligatorio faltante</informacionAdicional>
                            <tipo>ERROR</tipo>
                          </mensaje>
                        </mensajes>
                      </autorizacion>
                    </autorizaciones>
                  </RespuestaAutorizacionComprobante>
                </ns2:autorizacionComprobanteResponse>
              </soap:Body>
            </soap:Envelope>
            """.formatted(ACCESS_KEY);

    private static final String SOAP_NO_AUTORIZADO_RETRIABLE = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ns2:autorizacionComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.autorizacion">
                  <RespuestaAutorizacionComprobante>
                    <claveAccesoConsultada>%s</claveAccesoConsultada>
                    <numeroComprobantes>1</numeroComprobantes>
                    <autorizaciones>
                      <autorizacion>
                        <estado>NO AUTORIZADO</estado>
                        <comprobante><![CDATA[<factura/>]]></comprobante>
                        <mensajes>
                          <mensaje>
                            <identificador>70</identificador>
                            <mensaje>CLAVE DE ACCESO EN PROCESO</mensaje>
                            <tipo>ERROR</tipo>
                          </mensaje>
                        </mensajes>
                      </autorizacion>
                    </autorizaciones>
                  </RespuestaAutorizacionComprobante>
                </ns2:autorizacionComprobanteResponse>
              </soap:Body>
            </soap:Envelope>
            """.formatted(ACCESS_KEY);

    // ── Tests: parsing exitoso ──

    @Nested
    @DisplayName("parsing exitoso")
    class SuccessfulParsing {

        @Test
        @DisplayName("parsea respuesta AUTORIZADO con XML y número de autorización")
        void parsesAutorizado() {
            var response = SriAuthorizationResponseParser.parse(SOAP_AUTORIZADO);

            assertEquals(AuthorizationStatus.AUTORIZADO, response.status());
            assertTrue(response.isAuthorized());
            assertEquals(ACCESS_KEY, response.authorizationNumber());
            assertEquals("05/04/2026 10:30:45", response.authorizationDate());
            assertNotNull(response.authorizedXml());
            assertTrue(response.authorizedXml().contains("factura"));
            assertTrue(response.messages().isEmpty());
        }

        @Test
        @DisplayName("parsea respuesta AUTORIZADO con advertencia")
        void parsesAutorizadoWithWarning() {
            var response = SriAuthorizationResponseParser.parse(SOAP_AUTORIZADO_WITH_WARNING);

            assertTrue(response.isAuthorized());
            assertFalse(response.hasBusinessErrors());
            assertEquals(1, response.messages().size());

            var msg = response.messages().getFirst();
            assertEquals("60", msg.identifier());
            assertEquals("CONTRIBUYENTE ESPECIAL", msg.message());
            assertEquals("El emisor es contribuyente especial", msg.additionalInfo());
            assertEquals("ADVERTENCIA", msg.type());
        }

        @Test
        @DisplayName("parsea respuesta NO AUTORIZADO con error de negocio")
        void parsesNoAutorizadoBusinessError() {
            var response = SriAuthorizationResponseParser.parse(SOAP_NO_AUTORIZADO);

            assertEquals(AuthorizationStatus.NO_AUTORIZADO, response.status());
            assertFalse(response.isAuthorized());
            assertTrue(response.hasBusinessErrors());

            var msg = response.messages().getFirst();
            assertEquals("35", msg.identifier());
            assertEquals("DOCUMENTO PREVIAMENTE RECIBIDO", msg.message());
            assertTrue(msg.isBusinessError());
        }

        @Test
        @DisplayName("parsea respuesta NO AUTORIZADO con múltiples errores")
        void parsesMultipleErrors() {
            var response = SriAuthorizationResponseParser.parse(SOAP_NO_AUTORIZADO_MULTIPLE_ERRORS);

            assertFalse(response.isAuthorized());
            assertEquals(2, response.messages().size());

            assertEquals("45", response.messages().get(0).identifier());
            assertEquals("52", response.messages().get(1).identifier());
            assertTrue(response.messages().stream().allMatch(m -> m.isBusinessError()));
        }

        @Test
        @DisplayName("parsea respuesta NO AUTORIZADO con error reintentable")
        void parsesRetriableError() {
            var response = SriAuthorizationResponseParser.parse(SOAP_NO_AUTORIZADO_RETRIABLE);

            assertFalse(response.isAuthorized());
            assertFalse(response.hasBusinessErrors());

            var msg = response.messages().getFirst();
            assertEquals("70", msg.identifier());
            assertFalse(msg.isBusinessError());
        }

        @Test
        @DisplayName("extrae XML autorizado del CDATA")
        void extractsAuthorizedXml() {
            var response = SriAuthorizationResponseParser.parse(SOAP_AUTORIZADO);
            assertNotNull(response.authorizedXml());
            assertTrue(response.authorizedXml().contains("infoTributaria"));
            assertTrue(response.authorizedXml().contains("razonSocial"));
        }

        @Test
        @DisplayName("extrae número de autorización")
        void extractsAuthorizationNumber() {
            var response = SriAuthorizationResponseParser.parse(SOAP_AUTORIZADO);
            assertEquals(ACCESS_KEY, response.authorizationNumber());
        }

        @Test
        @DisplayName("extrae fecha de autorización")
        void extractsAuthorizationDate() {
            var response = SriAuthorizationResponseParser.parse(SOAP_AUTORIZADO);
            assertEquals("05/04/2026 10:30:45", response.authorizationDate());
        }

        @Test
        @DisplayName("respuesta NO AUTORIZADO no tiene número de autorización")
        void noAuthorizadoHasNoAuthorizationNumber() {
            var response = SriAuthorizationResponseParser.parse(SOAP_NO_AUTORIZADO);
            assertNull(response.authorizationNumber());
        }

        @Test
        @DisplayName("respuesta NO AUTORIZADO no tiene fecha de autorización")
        void noAuthorizadoHasNoAuthorizationDate() {
            var response = SriAuthorizationResponseParser.parse(SOAP_NO_AUTORIZADO);
            assertNull(response.authorizationDate());
        }

        @Test
        @DisplayName("extrae clave de acceso consultada")
        void extractsAccessKey() {
            var response = SriAuthorizationResponseParser.parse(SOAP_AUTORIZADO);
            assertEquals(ACCESS_KEY, response.accessKey());
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
                    () -> SriAuthorizationResponseParser.parse(null));
        }

        @Test
        @DisplayName("respuesta vacía lanza excepción")
        void emptyResponseThrows() {
            assertThrows(SriException.class,
                    () -> SriAuthorizationResponseParser.parse(""));
        }

        @Test
        @DisplayName("respuesta en blanco lanza excepción")
        void blankResponseThrows() {
            assertThrows(SriException.class,
                    () -> SriAuthorizationResponseParser.parse("   "));
        }

        @Test
        @DisplayName("XML inválido lanza excepción")
        void invalidXmlThrows() {
            assertThrows(SriException.class,
                    () -> SriAuthorizationResponseParser.parse("<not-closed"));
        }

        @Test
        @DisplayName("XML sin elemento autorizacion lanza excepción")
        void missingAutorizacionThrows() {
            var xml = """
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                      <soap:Body>
                        <RespuestaAutorizacionComprobante>
                          <autorizaciones/>
                        </RespuestaAutorizacionComprobante>
                      </soap:Body>
                    </soap:Envelope>
                    """;
            assertThrows(SriException.class,
                    () -> SriAuthorizationResponseParser.parse(xml));
        }

        @Test
        @DisplayName("XML sin estado lanza excepción")
        void missingEstadoThrows() {
            var xml = """
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                      <soap:Body>
                        <RespuestaAutorizacionComprobante>
                          <autorizaciones>
                            <autorizacion>
                              <numeroAutorizacion>123</numeroAutorizacion>
                            </autorizacion>
                          </autorizaciones>
                        </RespuestaAutorizacionComprobante>
                      </soap:Body>
                    </soap:Envelope>
                    """;
            assertThrows(SriException.class,
                    () -> SriAuthorizationResponseParser.parse(xml));
        }

        @Test
        @DisplayName("estado desconocido lanza excepción")
        void unknownEstadoThrows() {
            var xml = """
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                      <soap:Body>
                        <RespuestaAutorizacionComprobante>
                          <autorizaciones>
                            <autorizacion>
                              <estado>DESCONOCIDO</estado>
                            </autorizacion>
                          </autorizaciones>
                        </RespuestaAutorizacionComprobante>
                      </soap:Body>
                    </soap:Envelope>
                    """;
            assertThrows(SriException.class,
                    () -> SriAuthorizationResponseParser.parse(xml));
        }
    }
}
