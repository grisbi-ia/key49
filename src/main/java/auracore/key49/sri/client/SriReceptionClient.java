package auracore.key49.sri.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Timeout;

import auracore.key49.core.model.enums.SriEnvironment;
import auracore.key49.sri.SriException;
import auracore.key49.sri.config.SriEndpoints;
import auracore.key49.sri.model.SriReceptionResponse;
import auracore.key49.sri.parser.SriReceptionResponseParser;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Cliente SOAP para el servicio de Recepción de Comprobantes del SRI.
 *
 * <p>
 * Envía el XML firmado (codificado en Base64) al endpoint SOAP
 * {@code validarComprobante} y parsea la respuesta (RECIBIDA / DEVUELTA).
 *
 * <p>
 * Incorpora circuit breaker y timeout para resilencia ante fallos del SRI.
 */
@ApplicationScoped
public class SriReceptionClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static final String SOAP_ACTION = "";
    private static final String CONTENT_TYPE = "text/xml; charset=utf-8";
    private static final String RECEPTION_NS = "http://ec.gob.sri.ws.recepcion";

    private final HttpClient httpClient;

    @Inject
    SriEndpoints sriEndpoints;

    public SriReceptionClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    // Constructor para tests (inyección de HttpClient)
    SriReceptionClient(HttpClient httpClient, SriEndpoints sriEndpoints) {
        this.httpClient = httpClient;
        this.sriEndpoints = sriEndpoints;
    }

    /**
     * Envía un comprobante XML firmado al servicio de Recepción del SRI.
     *
     * @param signedXml XML firmado del comprobante (String)
     * @param environment ambiente del SRI (TEST o PRODUCTION)
     * @return respuesta del SRI con estado y mensajes
     * @throws SriException si la comunicación falla o la respuesta no puede ser
     * parseada
     */
    @Blocking
    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 30000,
            successThreshold = 3
    )
    @Timeout(3000)
    public SriReceptionResponse send(String signedXml, SriEnvironment environment) {
        if (signedXml == null || signedXml.isBlank()) {
            throw new SriException("Signed XML must not be null or blank");
        }
        if (environment == null) {
            throw new SriException("SRI environment must not be null");
        }

        var url = sriEndpoints.receptionUrl(environment);
        var base64Xml = Base64.getEncoder().encodeToString(
                signedXml.getBytes(StandardCharsets.UTF_8));
        var soapEnvelope = buildSoapEnvelope(base64Xml);

        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(READ_TIMEOUT)
                    .header("Content-Type", CONTENT_TYPE)
                    .header("SOAPAction", SOAP_ACTION)
                    .POST(HttpRequest.BodyPublishers.ofString(soapEnvelope, StandardCharsets.UTF_8))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                throw new SriException("SRI returned HTTP " + response.statusCode());
            }

            return SriReceptionResponseParser.parse(response.body());
        } catch (SriException e) {
            throw e;
        } catch (java.net.http.HttpConnectTimeoutException e) {
            throw new SriException("Connection timeout to SRI reception service", e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new SriException("Read timeout from SRI reception service", e);
        } catch (java.io.IOException e) {
            throw new SriException("I/O error communicating with SRI reception service", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SriException("Interrupted while communicating with SRI reception service", e);
        }
    }

    /**
     * Construye el sobre SOAP para el servicio de Recepción.
     */
    static String buildSoapEnvelope(String base64Xml) {
        return """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" \
                xmlns:ec="%s">
                  <soapenv:Header/>
                  <soapenv:Body>
                    <ec:validarComprobante>
                      <xml>%s</xml>
                    </ec:validarComprobante>
                  </soapenv:Body>
                </soapenv:Envelope>""".formatted(RECEPTION_NS, base64Xml);
    }
}
