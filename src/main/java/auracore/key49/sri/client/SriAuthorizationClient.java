package auracore.key49.sri.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Timeout;

import auracore.key49.core.model.enums.SriEnvironment;
import auracore.key49.sri.SriException;
import auracore.key49.sri.config.SriEndpoints;
import auracore.key49.sri.model.SriAuthorizationResponse;
import auracore.key49.sri.parser.SriAuthorizationResponseParser;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Cliente SOAP para el servicio de Autorización de Comprobantes del SRI.
 *
 * <p>
 * Consulta el estado de autorización de un comprobante a través de su clave de
 * acceso (49 dígitos) en el endpoint SOAP {@code autorizacionComprobante}.
 *
 * <p>
 * Incorpora circuit breaker y timeout para resilencia ante fallos del SRI.
 */
@ApplicationScoped
public class SriAuthorizationClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static final String SOAP_ACTION = "";
    private static final String CONTENT_TYPE = "text/xml; charset=utf-8";
    private static final String AUTHORIZATION_NS = "http://ec.gob.sri.ws.autorizacion";

    private final HttpClient httpClient;

    @Inject
    SriEndpoints sriEndpoints;

    public SriAuthorizationClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    // Constructor para tests (inyección de HttpClient)
    SriAuthorizationClient(HttpClient httpClient, SriEndpoints sriEndpoints) {
        this.httpClient = httpClient;
        this.sriEndpoints = sriEndpoints;
    }

    /**
     * Consulta el estado de autorización de un comprobante en el SRI.
     *
     * @param accessKey clave de acceso de 49 dígitos
     * @param environment ambiente del SRI (TEST o PRODUCTION)
     * @return respuesta del SRI con estado de autorización, XML autorizado y
     * mensajes
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
    @Timeout(5000)
    public SriAuthorizationResponse authorize(String accessKey, SriEnvironment environment) {
        if (accessKey == null || accessKey.isBlank()) {
            throw new SriException("Access key must not be null or blank");
        }
        if (accessKey.length() != 49) {
            throw new SriException("Access key must be exactly 49 digits, got " + accessKey.length());
        }
        if (environment == null) {
            throw new SriException("SRI environment must not be null");
        }

        var url = sriEndpoints.authorizationUrl(environment);
        var soapEnvelope = buildSoapEnvelope(accessKey);

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

            return SriAuthorizationResponseParser.parse(response.body());
        } catch (SriException e) {
            throw e;
        } catch (java.net.http.HttpConnectTimeoutException e) {
            throw new SriException("Connection timeout to SRI authorization service", e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new SriException("Read timeout from SRI authorization service", e);
        } catch (java.io.IOException e) {
            throw new SriException("I/O error communicating with SRI authorization service", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SriException("Interrupted while communicating with SRI authorization service", e);
        }
    }

    /**
     * Construye el sobre SOAP para el servicio de Autorización.
     */
    static String buildSoapEnvelope(String accessKey) {
        return """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" \
                xmlns:ec="%s">
                  <soapenv:Header/>
                  <soapenv:Body>
                    <ec:autorizacionComprobante>
                      <claveAccesoComprobante>%s</claveAccesoComprobante>
                    </ec:autorizacionComprobante>
                  </soapenv:Body>
                </soapenv:Envelope>""".formatted(AUTHORIZATION_NS, accessKey);
    }
}
