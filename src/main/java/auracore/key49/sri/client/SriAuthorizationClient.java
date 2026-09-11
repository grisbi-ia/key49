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
import auracore.key49.sri.SriNotRegisteredException;
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

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(25);

    private static final String AUTHORIZATION_NS = "http://ec.gob.sri.ws.autorizacion";

    private final HttpClient httpClient;

    @Inject
    SriEndpoints sriEndpoints;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "key49.sri.authorization.throttle-ms", defaultValue = "300")
    long throttleMs;

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
            successThreshold = 3,
            skipOn = SriNotRegisteredException.class
    )
    @Timeout(25000)
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
            // SriSoapHttp maneja el 302 intermitente del SRI reintentando el endpoint
            var response = SriSoapHttp.post(httpClient, url, soapEnvelope, READ_TIMEOUT);
            // Espaciado entre consultas (el SRI rechaza consultas paralelas)
            throttle();
            return SriAuthorizationResponseParser.parse(response.body());
        } catch (SriException e) {
            throw e;
        } catch (Exception e) {
            throw new SriException("Unexpected error communicating with SRI authorization service", e);
        }
    }

    private void throttle() {
        if (throttleMs <= 0) {
            return;
        }
        try {
            Thread.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
