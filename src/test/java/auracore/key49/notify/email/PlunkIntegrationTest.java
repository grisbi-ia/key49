package auracore.key49.notify.email;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de integración de PlunkClient contra un servidor HTTP fake que
 * simula la API de Plunk (useplunk.com).
 *
 * <p>No requiere red ni credenciales reales de Plunk. Cada test levanta
 * un servidor fake independiente para evitar interferencias entre tests.</p>
 */
class PlunkIntegrationTest {

    private static final String API_KEY = "sk_test_integration_fake_key";
    private static final String ORIGINAL_BASE_URL = "https://next-api.useplunk.com/v1";

    private HttpServer server;
    private int port;
    private final List<CapturedRequest> captured = new ArrayList<>();

    // Respuestas configurables por test
    private int verifyCode = 200;
    private int sendCode = 200;
    private int trackCode = 200;
    private String verifyJson;
    private String sendJson;

    record CapturedRequest(String method, String path, String authHeader, String body) {}

    @BeforeEach
    void setUp() throws IOException {
        captured.clear();
        verifyCode = 200;
        sendCode = 200;
        trackCode = 200;
        verifyJson = null;
        sendJson = null;

        server = HttpServer.create(new InetSocketAddress(0), 0);

        // POST /v1/verify
        server.createContext("/v1/verify", exchange -> {
            captured.add(capture(exchange));
            String body = verifyJson != null ? verifyJson
                    : "{\"valid\":true,\"hasMxRecords\":true,\"domainExists\":true,"
                    + "\"isDisposable\":false,\"isTypo\":false,\"reasons\":[]}";
            respond(exchange, verifyCode, body);
        });

        // POST /v1/send
        server.createContext("/v1/send", exchange -> {
            captured.add(capture(exchange));
            String body = sendJson != null ? sendJson : "{\"success\":true}";
            respond(exchange, sendCode, body);
        });

        // POST /v1/track
        server.createContext("/v1/track", exchange -> {
            captured.add(capture(exchange));
            respond(exchange, trackCode, "{\"success\":true}");
        });

        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
        PlunkClient.BASE_URL = "http://localhost:" + port + "/v1";
    }

    @AfterEach
    void tearDown() {
        PlunkClient.BASE_URL = ORIGINAL_BASE_URL;
        if (server != null) {
            server.stop(0);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private CapturedRequest capture(HttpExchange exchange) throws IOException {
        var auth = exchange.getRequestHeaders().getFirst("Authorization");
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                auth,
                body);
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String validVerifyJson() {
        return "{\"valid\":true,\"hasMxRecords\":true,\"domainExists\":true,"
                + "\"isDisposable\":false,\"isTypo\":false,\"reasons\":[]}";
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tests: Verify
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("verify: email válido")
    void verifyValidEmail() {
        var result = PlunkClient.verify(API_KEY, "test@example.com");

        assertTrue(result.valid());
        assertTrue(result.hasMxRecords());
        assertTrue(result.domainExists());
        assertFalse(result.isDisposable());
        assertFalse(result.isTypo());

        assertEquals(1, captured.size());
        var req = captured.get(0);
        assertEquals("POST", req.method());
        assertEquals("/v1/verify", req.path());
        assertEquals("Bearer " + API_KEY, req.authHeader());
        assertTrue(req.body().contains("test@example.com"));
    }

    @Test
    @DisplayName("verify: email inválido sin MX")
    void verifyNoMx() {
        verifyJson = "{\"valid\":false,\"hasMxRecords\":false,\"domainExists\":true,"
                + "\"isDisposable\":false,\"isTypo\":false,\"reasons\":[\"no_mx_records\"]}";

        var result = PlunkClient.verify(API_KEY, "bad@nomx.com");

        assertFalse(result.valid());
        assertFalse(result.hasMxRecords());
        assertEquals(List.of("no_mx_records"), result.reasons());
    }

    @Test
    @DisplayName("verify: email desechable detectado")
    void verifyDisposable() {
        verifyJson = "{\"valid\":true,\"hasMxRecords\":true,\"domainExists\":true,"
                + "\"isDisposable\":true,\"isTypo\":false,\"reasons\":[]}";

        var result = PlunkClient.verify(API_KEY, "trash@mailinator.com");

        assertTrue(result.valid());
        assertTrue(result.isDisposable());
    }

    @Test
    @DisplayName("verify: error 500 → soft-fail (unavailable)")
    void verifyServerErrorSoftFails() {
        verifyCode = 500;

        var result = PlunkClient.verify(API_KEY, "any@example.com");

        // Soft-fail: asume válido
        assertTrue(result.valid(), "Should soft-fail to valid=true");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tests: Send
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("send: exitoso con adjuntos")
    void sendWithAttachments() {
        var from = new PlunkClient.SendRequest.SenderInfo("Key49", "fact@key49.ec");
        var attachments = List.of(
                PlunkClient.Attachment.of("doc.pdf", "BYTES".getBytes(StandardCharsets.UTF_8),
                        "application/pdf"),
                PlunkClient.Attachment.of("doc.xml", "<x/>".getBytes(StandardCharsets.UTF_8),
                        "application/xml"));
        var req = new PlunkClient.SendRequest("cli@test.com", from,
                "Factura 001-001-000000001", "<html>B</html>", attachments);

        PlunkClient.send(API_KEY, req);

        assertEquals(1, captured.size());
        var body = captured.get(0).body();
        assertTrue(body.contains("\"to\":\"cli@test.com\""));
        assertTrue(body.contains("\"from\":\"fact@key49.ec\""));
        assertTrue(body.contains("\"subject\":\"Factura 001-001-000000001\""));
        assertTrue(body.contains("\"contentType\":\"application/pdf\""));
        assertTrue(body.contains("\"contentType\":\"application/xml\""));
    }

    @Test
    @DisplayName("send: sin adjuntos → array vacío")
    void sendWithoutAttachments() {
        var from = new PlunkClient.SendRequest.SenderInfo("K", "k@k.ec");
        var req = new PlunkClient.SendRequest("u@t.com", from, "S", "<p>H</p>", List.of());

        PlunkClient.send(API_KEY, req);

        assertTrue(captured.get(0).body().contains("\"attachments\":[]"));
    }

    @Test
    @DisplayName("send: HTTP 400 → EmailSendException")
    void sendHttp400() {
        sendCode = 400;
        var from = new PlunkClient.SendRequest.SenderInfo("K", "k@k.ec");
        var req = new PlunkClient.SendRequest("x@x.com", from, "S", "B", List.of());

        assertThrows(EmailSendException.class, () -> PlunkClient.send(API_KEY, req));
    }

    @Test
    @DisplayName("send: from es solo email, no 'Name <email>'")
    void sendFromIsEmailOnly() {
        var from = new PlunkClient.SendRequest.SenderInfo("Mi Empresa SA", "fact@empresa.ec");
        var req = new PlunkClient.SendRequest("cli@test.com", from, "S", "B", List.of());

        PlunkClient.send(API_KEY, req);

        var body = captured.get(0).body();
        // Plunk requiere solo el email en el campo "from"
        assertTrue(body.contains("\"from\":\"fact@empresa.ec\""));
        assertFalse(body.contains("Mi Empresa"), "from field should be email-only, no name");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tests: Track
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("track: evento exitoso")
    void trackSuccess() {
        PlunkClient.track(API_KEY, "cli@test.com", "document.sent",
                Map.of("access_key", "12345", "document_type", "Factura"));

        assertEquals(1, captured.size());
        var body = captured.get(0).body();
        assertTrue(body.contains("\"event\":\"document.sent\""));
        assertTrue(body.contains("\"subscribed\":false"));
        assertTrue(body.contains("\"access_key\":\"12345\""));
    }

    @Test
    @DisplayName("track: 401 NO lanza excepción (soft-fail)")
    void track401softFails() {
        trackCode = 401;

        assertDoesNotThrow(() ->
                PlunkClient.track(API_KEY, "t@t.com", "document.sent", Map.of()));
    }

    @Test
    @DisplayName("track: sin datos → data vacío")
    void trackNoData() {
        PlunkClient.track(API_KEY, "t@t.com", "email.opened", null);

        assertTrue(captured.get(0).body().contains("\"data\":{}"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tests: Flujo completo PlunkEmailSender
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("sendPlatform: verify OK + send OK (2 requests)")
    void sendPlatformFullFlow() {
        PlunkEmailSender.sendPlatform(API_KEY, "noreply@key49.ec", "Key49",
                "user@example.com", "Verifica tu email", "<html>V</html>");

        // verify + send = 2 requests (track no se llama en sendPlatform)
        assertEquals(2, captured.size());
        assertEquals("/v1/verify", captured.get(0).path());
        assertEquals("/v1/send", captured.get(1).path());
    }

    @Test
    @DisplayName("sendPlatform: verify rechaza → EmailSendException, 1 solo request")
    void sendPlatformRejected() {
        verifyJson = "{\"valid\":false,\"hasMxRecords\":false,\"domainExists\":false,"
                + "\"isDisposable\":false,\"isTypo\":false,\"reasons\":[\"domain_not_found\"]}";

        var ex = assertThrows(EmailSendException.class,
                () -> PlunkEmailSender.sendPlatform(API_KEY, "n@k.ec", "K",
                        "bad@noexist.com", "S", "B"));

        assertTrue(ex.getMessage().contains("rejected by validation"));
        // verify (rechaza) + track (delivery_aborted) = 2 requests
        assertEquals(2, captured.size());
        assertEquals("/v1/verify", captured.get(0).path());
        assertEquals("/v1/track", captured.get(1).path());
    }

    @Test
    @DisplayName("sendDocumentDelivery: verify + send + track (3 requests)")
    void sendDocumentDeliveryFullFlow() {
        byte[] pdf = "PDF".getBytes(StandardCharsets.UTF_8);
        byte[] xml = "<x/>".getBytes(StandardCharsets.UTF_8);

        PlunkEmailSender.sendDocumentDelivery(
                API_KEY, "fact@tenant.ec", "Mi Empresa",
                "cliente@gmail.com", "Factura 001-001-000000001", "<html>F</html>",
                pdf, "001-001-000000001.pdf",
                xml, "001-001-000000001.xml",
                "4901202501090000000000110010010000000011234567813",
                "Factura");

        assertEquals(3, captured.size());
        assertEquals("/v1/verify", captured.get(0).path());
        assertEquals("/v1/send", captured.get(1).path());
        assertEquals("/v1/track", captured.get(2).path());

        var trackBody = captured.get(2).body();
        assertTrue(trackBody.contains("\"event\":\"document.sent\""));
        assertTrue(trackBody.contains("490120250109"));
    }

    @Test
    @DisplayName("sendDocumentDelivery: verify unavailable → envía igual (soft-fail)")
    void sendDeliveryWithVerifyUnavailable() {
        verifyCode = 503; // unavailable → soft-fail

        PlunkEmailSender.sendDocumentDelivery(
                API_KEY, "f@t.ec", "E", "c@g.com", "S", "B",
                null, null, null, null,
                "4901202501090000000000110010010000000011234567813", "Factura");

        // verify (503 → soft-fail/unavailable) + send + track = 3 requests
        assertEquals(3, captured.size());
        assertEquals("/v1/verify", captured.get(0).path());
        assertEquals("/v1/send", captured.get(1).path());
        assertEquals("/v1/track", captured.get(2).path());
    }
}
