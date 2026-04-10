package auracore.key49.sri.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpServer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Recurso de test que inicia un servidor HTTP mock para simular el SRI.
 *
 * <p>
 * Permite controlar si el servidor retorna éxito (SOAP RECIBIDA) o error (HTTP
 * 500) para probar el comportamiento del circuit breaker.
 */
public class MockSriServerResource implements QuarkusTestResourceLifecycleManager {

    /**
     * Controla si el mock server retorna error (true) o éxito (false).
     */
    public static final AtomicBoolean SHOULD_FAIL = new AtomicBoolean(true);

    private HttpServer server;

    static final String SOAP_RECIBIDA = """
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
            </soap:Envelope>""";

    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start mock SRI server", e);
        }
        server.createContext("/reception", exchange -> {
            if (SHOULD_FAIL.get()) {
                exchange.sendResponseHeaders(500, 0);
                exchange.getResponseBody().close();
            } else {
                byte[] body = SOAP_RECIBIDA.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/xml; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        server.start();

        int port = server.getAddress().getPort();
        return Map.of(
                "key49.sri.url.test.reception",
                "http://localhost:" + port + "/reception",
                // Override CB delay to 2s for faster test recovery
                "auracore.key49.sri.client.SriReceptionClient/send/CircuitBreaker/delay",
                "2000");
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}
