package auracore.key49.notify.email;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

/**
 * Cliente HTTP para la API REST de Plunk (useplunk.com).
 *
 * <p>Encapsula las tres operaciones utilizadas por Key49:</p>
 * <ul>
 *   <li>{@link #verify} — valida la dirección de email destino antes de enviar.</li>
 *   <li>{@link #send} — envía el email con adjuntos (RIDE PDF + XML).</li>
 *   <li>{@link #track} — registra un evento de negocio asociado al contacto.</li>
 * </ul>
 *
 * <p>Esta clase es stateless e independiente de CDI. Los callers inyectan la
 * API key correspondiente (plataforma o tenant) en cada llamada.</p>
 */
public final class PlunkClient {

    private static final Logger log = Logger.getLogger(PlunkClient.class);

    static String BASE_URL = "https://next-api.useplunk.com/v1";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private PlunkClient() {
    }

    // ── Records de resultado ─────────────────────────────────────────────────

    /**
     * Resultado de la validación de email ({@code POST /v1/verify}).
     *
     * @param valid        true si el email es válido en todos los niveles
     * @param hasMxRecords true si el dominio tiene registros MX (acepta correo)
     * @param domainExists true si el dominio existe en DNS
     * @param isDisposable true si es un email desechable (Mailinator, Temp Mail, etc.)
     * @param isTypo       true si Plunk detectó posible error tipográfico
     * @param reasons      lista de razones de invalidez (vacía si valid=true)
     */
    public record VerifyResult(
            boolean valid,
            boolean hasMxRecords,
            boolean domainExists,
            boolean isDisposable,
            boolean isTypo,
            List<String> reasons) {

        /** Resultado de fallo seguro cuando el endpoint no está disponible. */
        static VerifyResult unavailable() {
            return new VerifyResult(true, true, true, false, false, List.of());
        }
    }

    /**
     * Adjunto para incluir en el email ({@code POST /v1/send}).
     *
     * @param filename nombre del archivo (ej: {@code 1234567890.pdf})
     * @param content  contenido en Base64
     * @param type     MIME type (ej: {@code application/pdf})
     */
    public record Attachment(String filename, String content, String type) {

        /** Construye un adjunto codificando los bytes en Base64. */
        public static Attachment of(String filename, byte[] bytes, String mimeType) {
            return new Attachment(filename, Base64.getEncoder().encodeToString(bytes), mimeType);
        }
    }

    /**
     * Request para envío de email ({@code POST /v1/send}).
     *
     * @param to          email destinatario
     * @param from        objeto con {@code name} y {@code email} del remitente
     * @param subject     asunto del email
     * @param body        cuerpo HTML
     * @param attachments lista de adjuntos (puede ser vacía)
     */
    public record SendRequest(
            String to,
            SenderInfo from,
            String subject,
            String body,
            List<Attachment> attachments) {

        public record SenderInfo(String name, String email) {
        }
    }

    // ── API pública ──────────────────────────────────────────────────────────

    /**
     * Valida una dirección de email contra la API de Plunk.
     *
     * <p>En caso de error de red o timeout, retorna {@link VerifyResult#unavailable()}
     * para no bloquear el flujo de envío (soft-fail).</p>
     *
     * @param apiKey API key secreta de Plunk (sk_*)
     * @param email  dirección a validar
     * @return resultado de la validación
     */
    public static VerifyResult verify(String apiKey, String email) {
        var body = """
                {"email":"%s"}""".formatted(escapeJson(email));
        try {
            var response = post(apiKey, "/verify", body);
            if (response.statusCode() != 200) {
                log.warnf("Plunk verify returned %d for email=%s — treating as valid (soft-fail)",
                        response.statusCode(), email);
                return VerifyResult.unavailable();
            }
            return parseVerifyResponse(response.body());
        } catch (Exception e) {
            log.warnf("Plunk verify unavailable for email=%s — treating as valid (soft-fail): %s",
                    email, e.getMessage());
            return VerifyResult.unavailable();
        }
    }

    /**
     * Envía un email transaccional vía Plunk con adjuntos opcionales.
     *
     * @param apiKey  API key secreta de Plunk (sk_*)
     * @param request datos del email
     * @throws EmailSendException si el envío falla
     */
    public static void send(String apiKey, SendRequest request) {
        var body = buildSendBody(request);
        try {
            var response = post(apiKey, "/send", body);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new EmailSendException(
                        "Plunk send failed with HTTP %d: %s".formatted(
                                response.statusCode(), truncate(response.body(), 1000)));
            }
            log.infof("Plunk send OK | to=%s subject=%s attachments=%d",
                    request.to(), request.subject(), request.attachments().size());
        } catch (EmailSendException e) {
            throw e;
        } catch (Exception e) {
            throw new EmailSendException("Plunk send error for to=" + request.to(), e);
        }
    }

    /**
     * Registra un evento de negocio en Plunk asociado a un contacto.
     *
     * <p>Soft-fail: los errores se loguean pero no propagan — el rastreo no debe
     * interrumpir el flujo principal.</p>
     *
     * @param apiKey    API key secreta de Plunk (sk_*)
     * @param email     email del contacto (identificador en Plunk)
     * @param eventName nombre del evento (ej: {@code document.sent})
     * @param data      metadatos adicionales del evento (puede ser vacío)
     */
    public static void track(String apiKey, String email, String eventName,
            Map<String, String> data) {
        try {
            var dataJson = buildDataJson(data);
            var body = """
                    {"email":"%s","event":"%s","subscribed":false,"data":%s}"""
                    .formatted(escapeJson(email), escapeJson(eventName), dataJson);
            var response = post(apiKey, "/track", body);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warnf("Plunk track returned %d | event=%s email=%s",
                        response.statusCode(), eventName, email);
            }
        } catch (Exception e) {
            log.warnf("Plunk track failed (soft-fail) | event=%s email=%s: %s",
                    eventName, email, e.getMessage());
        }
    }

    // ── Helpers HTTP ─────────────────────────────────────────────────────────

    private static HttpResponse<String> post(String apiKey, String path, String jsonBody)
            throws Exception {
        var client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    /**
     * Parseo manual del JSON de respuesta de /v1/verify.
     * Se evita añadir una dependencia de Jackson solo para este cliente.
     */
    static VerifyResult parseVerifyResponse(String json) {
        boolean valid       = extractBool(json, "valid", true);
        boolean hasMx       = extractBool(json, "hasMxRecords", true);
        boolean domainExists= extractBool(json, "domainExists", true);
        boolean disposable  = extractBool(json, "isDisposable", false);
        boolean isTypo      = extractBool(json, "isTypo", false);
        List<String> reasons = extractReasons(json);
        return new VerifyResult(valid, hasMx, domainExists, disposable, isTypo, reasons);
    }

    private static boolean extractBool(String json, String key, boolean defaultVal) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return defaultVal;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return defaultVal;
        String rest = json.substring(colon + 1).stripLeading();
        if (rest.startsWith("true")) return true;
        if (rest.startsWith("false")) return false;
        return defaultVal;
    }

    private static List<String> extractReasons(String json) {
        int start = json.indexOf("\"reasons\"");
        if (start < 0) return List.of();
        int arrStart = json.indexOf('[', start);
        int arrEnd   = json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return List.of();
        String arr = json.substring(arrStart + 1, arrEnd);
        if (arr.isBlank()) return List.of();
        var result = new ArrayList<String>();
        for (var part : arr.split(",")) {
            var s = part.strip().replace("\"", "");
            if (!s.isBlank()) result.add(s);
        }
        return List.copyOf(result);
    }

    // ── Builders de JSON ─────────────────────────────────────────────────────

    private static String buildSendBody(SendRequest req) {
        // Plunk requires "from" as a plain email address only (no "Name <email>" format)
        var attachJson = buildAttachmentsJson(req.attachments());
        return """
                {"to":"%s","from":"%s",\
                "subject":"%s","body":"%s",\
                "attachments":%s}"""
                .formatted(
                        escapeJson(req.to()),
                        escapeJson(req.from().email()),
                        escapeJson(req.subject()),
                        escapeJson(req.body()),
                        attachJson);
    }

    private static String buildAttachmentsJson(List<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return "[]";
        var sb = new StringBuilder("[");
        for (int i = 0; i < attachments.size(); i++) {
            var a = attachments.get(i);
            if (i > 0) sb.append(',');
            // Plunk expects "contentType", not "type"
            sb.append("{\"filename\":\"").append(escapeJson(a.filename())).append('"');
            sb.append(",\"content\":\"").append(a.content()).append('"');
            sb.append(",\"contentType\":\"").append(escapeJson(a.type())).append("\"}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String buildDataJson(Map<String, String> data) {
        if (data == null || data.isEmpty()) return "{}";
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : data.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(escapeJson(entry.getKey())).append("\":\"")
              .append(escapeJson(entry.getValue())).append('"');
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }

    static String escapeJson(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
