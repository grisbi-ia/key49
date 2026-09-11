package auracore.key49.sri.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import auracore.key49.sri.SriException;

/**
 * Envío de sobres SOAP a los servicios del SRI con manejo del {@code 302}
 * intermitente.
 *
 * <p>El SRI responde {@code 302} de forma intermitente redirigiendo a una IP
 * cuyo certificado TLS no coincide con su host (no es posible seguir el
 * redirect con verificación de certificado). Como el redirect es transitorio,
 * se <b>reintenta el endpoint original</b> con un pequeño backoff, preservando
 * siempre el método POST y el cuerpo SOAP.</p>
 */
final class SriSoapHttp {

    private static final int MAX_ATTEMPTS = 3;
    private static final String CONTENT_TYPE = "text/xml; charset=utf-8";
    private static final String SOAP_ACTION = "";

    private SriSoapHttp() {
    }

    /**
     * Envía el sobre SOAP por POST, reintentando ante redirects transitorios.
     *
     * @return la respuesta HTTP (status 200)
     * @throws SriException si tras {@value #MAX_ATTEMPTS} intentos no se obtiene
     *                      200, o ante error de red/timeout
     */
    static HttpResponse<String> post(HttpClient client, String url, String envelope, Duration timeout) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(timeout)
                        .header("Content-Type", CONTENT_TYPE)
                        .header("SOAPAction", SOAP_ACTION)
                        .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
                        .build();

                var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();

                if (status == 200) {
                    return response;
                }
                if (isRedirect(status)) {
                    sleep(150L * attempt);
                    continue;
                }
                throw new SriException("SRI returned HTTP " + status);
            } catch (SriException e) {
                throw e;
            } catch (java.net.http.HttpTimeoutException e) {
                throw new SriException("Timeout communicating with SRI service", e);
            } catch (IOException e) {
                throw new SriException("I/O error communicating with SRI service", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SriException("Interrupted while communicating with SRI service", e);
            }
        }
        throw new SriException("SRI returned repeated redirects (302) — endpoint inestable");
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
