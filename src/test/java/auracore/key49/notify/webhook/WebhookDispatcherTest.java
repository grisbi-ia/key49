package auracore.key49.notify.webhook;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.enums.DocumentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para WebhookDispatcher: firma HMAC, serialización, envío
 * HTTP, reintentos.
 */
class WebhookDispatcherTest {

    WebhookDispatcher dispatcher;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        dispatcher = new WebhookDispatcher();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        objectMapper.configure(
                com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        // Inyectar dependencias por reflexión
        setField("objectMapper", objectMapper);
        setField("connectTimeoutMs", 3000);
        setField("readTimeoutMs", 5000);
        setField("webhookEnabled", true);
        setField("ssrfValidationEnabled", false);
    }

    private void setField(String name, Object value) {
        try {
            var field = WebhookDispatcher.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(dispatcher, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Document sampleDocument() {
        var doc = new Document();
        doc.id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        doc.documentType = "01";
        doc.accessKey = "0504202601179001691900110010010000000421234567816";
        doc.status = DocumentStatus.AUTHORIZED;
        doc.issueDate = LocalDate.of(2026, 4, 5);
        doc.totalAmount = new BigDecimal("115.00");
        doc.recipientId = "1790016919001";
        doc.recipientName = "Empresa S.A.";
        doc.authorizationNumber = "0504202601179001691900110010010000000421234567816";
        doc.authorizationDate = Instant.parse("2026-04-05T20:00:00Z");
        return doc;
    }

    // ── Firma HMAC-SHA256 ──
    @Nested
    class SignatureTests {

        @Test
        void computeSignature_producesValidHmacSha256() throws Exception {
            String body = "{\"event\":\"document.authorized\"}";
            String secret = "test-secret-key";

            String signature = dispatcher.computeSignature(body, secret);

            // Verificar contra implementación independiente
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var expected = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));

            assertEquals(expected, signature);
        }

        @Test
        void computeSignature_differentSecrets_produceDifferentSignatures() {
            String body = "{\"event\":\"test\"}";

            String sig1 = dispatcher.computeSignature(body, "secret-a");
            String sig2 = dispatcher.computeSignature(body, "secret-b");

            assertNotEquals(sig1, sig2);
        }

        @Test
        void computeSignature_sameBodiesAndSecret_produceSameSignature() {
            String body = "{\"event\":\"document.authorized\"}";
            String secret = "my-secret";

            assertEquals(
                    dispatcher.computeSignature(body, secret),
                    dispatcher.computeSignature(body, secret));
        }

        @Test
        void computeSignature_differentBodies_produceDifferentSignatures() {
            String secret = "my-secret";

            String sig1 = dispatcher.computeSignature("{\"a\":1}", secret);
            String sig2 = dispatcher.computeSignature("{\"a\":2}", secret);

            assertNotEquals(sig1, sig2);
        }

        @Test
        void computeSignature_returns64CharHex() {
            String signature = dispatcher.computeSignature("body", "secret");
            assertEquals(64, signature.length());
            assertTrue(signature.matches("[0-9a-f]{64}"));
        }
    }

    // ── Payload Building ──
    @Nested
    class PayloadTests {

        @Test
        void buildPayload_mapsAllFields() {
            var doc = sampleDocument();
            var payload = dispatcher.buildPayload(doc, "document.authorized");

            assertEquals("document.authorized", payload.event());
            assertEquals(doc.id.toString(), payload.documentId());
            assertEquals("01", payload.documentType());
            assertEquals(doc.accessKey, payload.accessKey());
            assertEquals("AUTHORIZED", payload.status());
            assertEquals(doc.issueDate, payload.issueDate());
            assertEquals(doc.totalAmount, payload.totalAmount());
            assertEquals(doc.recipientId, payload.recipientId());
            assertEquals(doc.recipientName, payload.recipientName());
            assertEquals(doc.authorizationNumber, payload.authorizationNumber());
            assertEquals(doc.authorizationDate, payload.authorizationDate());
            assertNotNull(payload.timestamp());
        }

        @Test
        void buildPayload_handlesNullAuthorizationFields() {
            var doc = sampleDocument();
            doc.authorizationNumber = null;
            doc.authorizationDate = null;

            var payload = dispatcher.buildPayload(doc, "document.rejected");

            assertEquals("document.rejected", payload.event());
            assertNull(payload.authorizationNumber());
            assertNull(payload.authorizationDate());
        }

        @Test
        void serializePayload_producesValidJson() throws Exception {
            var doc = sampleDocument();
            var payload = dispatcher.buildPayload(doc, "document.authorized");

            String json = dispatcher.serializePayload(payload);

            assertNotNull(json);
            assertTrue(json.contains("\"event\""));
            assertTrue(json.contains("\"document_id\""));
            assertTrue(json.contains("document.authorized"));

            // Verificar que es JSON válido
            var tree = objectMapper.readTree(json);
            assertEquals("document.authorized", tree.get("event").asText());
        }

        @Test
        void serializePayload_usesSnakeCaseNaming() {
            var doc = sampleDocument();
            var payload = dispatcher.buildPayload(doc, "document.authorized");

            String json = dispatcher.serializePayload(payload);

            assertTrue(json.contains("document_id"));
            assertTrue(json.contains("document_type"));
            assertTrue(json.contains("access_key"));
            assertTrue(json.contains("issue_date"));
            assertTrue(json.contains("total_amount"));
            assertTrue(json.contains("recipient_id"));
            assertTrue(json.contains("recipient_name"));
            assertTrue(json.contains("authorization_number"));
            assertTrue(json.contains("authorization_date"));
            assertFalse(json.contains("documentId"));
            assertFalse(json.contains("accessKey"));
        }
    }

    // ── Retry Delays ──
    @Nested
    class RetryDelayTests {

        @Test
        void calculateNextRetryAt_attempt1_10seconds() {
            var before = Instant.now();
            var next = WebhookDispatcher.calculateNextRetryAt(1);
            var after = Instant.now();

            assertTrue(next.isAfter(before.plusSeconds(9)));
            assertTrue(next.isBefore(after.plusSeconds(11)));
        }

        @Test
        void calculateNextRetryAt_attempt2_60seconds() {
            var before = Instant.now();
            var next = WebhookDispatcher.calculateNextRetryAt(2);

            assertTrue(next.isAfter(before.plusSeconds(59)));
            assertTrue(next.isBefore(before.plusSeconds(62)));
        }

        @Test
        void calculateNextRetryAt_attempt3_300seconds() {
            var before = Instant.now();
            var next = WebhookDispatcher.calculateNextRetryAt(3);

            assertTrue(next.isAfter(before.plusSeconds(299)));
            assertTrue(next.isBefore(before.plusSeconds(302)));
        }

        @Test
        void calculateNextRetryAt_beyondMax_usesLastDelay() {
            var before = Instant.now();
            var next = WebhookDispatcher.calculateNextRetryAt(10);

            // Debe usar el último delay (300s)
            assertTrue(next.isAfter(before.plusSeconds(299)));
        }

        @Test
        void retryDelays_correctValues() {
            assertArrayEquals(new long[]{10, 60, 300}, WebhookDispatcher.RETRY_DELAYS_SECONDS);
        }
    }

    // ── HTTP Dispatch ──
    @Nested
    class DispatchTests {

        HttpServer server;

        @AfterEach
        void tearDown() {
            if (server != null) {
                server.stop(0);
            }
        }

        @Test
        void dispatch_successReturnsDeliveredStatus() throws Exception {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/webhook", exchange -> {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("OK".getBytes());
                exchange.getResponseBody().close();
            });
            server.start();
            int port = server.getAddress().getPort();

            var doc = sampleDocument();
            var delivery = dispatcher.dispatch(
                    "http://localhost:" + port + "/webhook",
                    "test-secret",
                    doc,
                    "document.authorized");

            assertNotNull(delivery);
            assertEquals("delivered", delivery.status);
            assertEquals(200, delivery.responseStatus);
            assertEquals("document.authorized", delivery.eventType);
            assertNotNull(delivery.durationMs);
            assertTrue(delivery.durationMs >= 0);
        }

        @Test
        void dispatch_serverError_marksAsPendingWithRetry() throws Exception {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/webhook", exchange -> {
                byte[] body = "Server Error".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
            });
            server.start();
            int port = server.getAddress().getPort();

            var doc = sampleDocument();
            var delivery = dispatcher.dispatch(
                    "http://localhost:" + port + "/webhook",
                    "test-secret",
                    doc,
                    "document.authorized");

            assertNotNull(delivery);
            assertEquals("pending", delivery.status);
            assertEquals(500, delivery.responseStatus);
            assertNotNull(delivery.nextAttemptAt);
        }

        @Test
        void dispatch_connectionRefused_marksAsFailed() {
            var doc = sampleDocument();
            // Puerto que nadie escucha
            var delivery = dispatcher.dispatch(
                    "http://localhost:19999/webhook",
                    "test-secret",
                    doc,
                    "document.authorized");

            assertNotNull(delivery);
            // attempt=1 < maxAttempts=3, so status should be "pending"
            assertEquals("pending", delivery.status);
            assertNull(delivery.responseStatus);
            assertNotNull(delivery.nextAttemptAt);
        }

        @Test
        void dispatch_sendsCorrectHeaders() throws Exception {
            var receivedHeaders = new AtomicReference<java.util.Map<String, java.util.List<String>>>();

            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/webhook", exchange -> {
                receivedHeaders.set(exchange.getRequestHeaders());
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("OK".getBytes());
                exchange.getResponseBody().close();
            });
            server.start();
            int port = server.getAddress().getPort();

            var doc = sampleDocument();
            dispatcher.dispatch(
                    "http://localhost:" + port + "/webhook",
                    "test-secret",
                    doc,
                    "document.authorized");

            var headers = receivedHeaders.get();
            assertNotNull(headers);
            assertTrue(headers.containsKey("X-key49-signature"));
            assertTrue(headers.get("X-key49-signature").getFirst().startsWith("sha256="));
            assertTrue(headers.containsKey("X-key49-event"));
            assertEquals("document.authorized", headers.get("X-key49-event").getFirst());
            assertTrue(headers.containsKey("Content-type"));
            assertEquals("application/json", headers.get("Content-type").getFirst());
        }

        @Test
        void dispatch_signatureIsVerifiable() throws Exception {
            var receivedBody = new AtomicReference<String>();
            var receivedSignature = new AtomicReference<String>();
            String secret = "verification-secret";

            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/webhook", exchange -> {
                receivedSignature.set(exchange.getRequestHeaders().getFirst("X-Key49-Signature"));
                receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("OK".getBytes());
                exchange.getResponseBody().close();
            });
            server.start();
            int port = server.getAddress().getPort();

            var doc = sampleDocument();
            dispatcher.dispatch("http://localhost:" + port + "/webhook", secret, doc, "document.authorized");

            // Verificar firma independientemente
            String expectedSignature = dispatcher.computeSignature(receivedBody.get(), secret);
            assertEquals("sha256=" + expectedSignature, receivedSignature.get());
        }

        @Test
        void dispatch_whenDisabled_returnsNull() {
            setField("webhookEnabled", false);

            var doc = sampleDocument();
            var delivery = dispatcher.dispatch("http://localhost/webhook", "secret", doc, "document.authorized");

            assertNull(delivery);
        }
    }
}
