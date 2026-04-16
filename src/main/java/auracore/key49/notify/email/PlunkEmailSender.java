package auracore.key49.notify.email;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

/**
 * Orquestador de envío de email vía Plunk.
 *
 * <p>Aplica el flujo completo para cada envío:</p>
 * <ol>
 *   <li>Valida la dirección destino ({@code POST /v1/verify}).</li>
 *   <li>Envía el email con adjuntos ({@code POST /v1/send}).</li>
 *   <li>Registra el evento en Plunk ({@code POST /v1/track}) — soft-fail.</li>
 * </ol>
 *
 * <p>La validación es <em>soft-fail</em>: si el endpoint de verificación no
 * está disponible se asume válido. Si el email es inválido (MX ausente, dominio
 * inexistente) se aborta el envío y se lanza {@link EmailSendException}.
 * Emails desechables o con typo se loguean como advertencia pero se envían.</p>
 *
 * <p>Esta clase es stateless. El {@code apiKey} se pasa en cada llamada para
 * soportar tanto la clave de plataforma como las claves por tenant.</p>
 */
public final class PlunkEmailSender {

    private static final Logger log = Logger.getLogger(PlunkEmailSender.class);

    private PlunkEmailSender() {
    }

    // ── API pública ──────────────────────────────────────────────────────────

    /**
     * Envía un email HTML sin adjuntos (emails de plataforma: verificación,
     * contraseña, alertas de plan).
     *
     * @param apiKey      API key de Plunk (sk_*)
     * @param fromEmail   email del remitente (dominio verificado en Plunk)
     * @param fromName    nombre visible del remitente
     * @param to          email del destinatario
     * @param subject     asunto
     * @param htmlBody    cuerpo HTML
     * @throws EmailSendException si la validación falla o el envío falla
     */
    public static void sendPlatform(String apiKey, String fromEmail, String fromName,
            String to, String subject, String htmlBody) {
        validateAndSend(apiKey, fromEmail, fromName, to, subject, htmlBody, List.of(), null);
    }

    /**
     * Envía el email de entrega de comprobante con RIDE (PDF) y XML adjuntos.
     *
     * @param apiKey       API key de Plunk del tenant (sk_*)
     * @param fromEmail    email remitente (dominio verificado en Plunk del tenant)
     * @param fromName     nombre del emisor (razón social del tenant)
     * @param to           email del receptor del comprobante
     * @param subject      asunto
     * @param htmlBody     cuerpo HTML
     * @param ridePdf      bytes del RIDE (PDF) — puede ser null
     * @param rideName     nombre del archivo PDF (ej: {@code 1234567890.pdf})
     * @param authorizedXml bytes del XML autorizado — puede ser null
     * @param xmlName      nombre del archivo XML (ej: {@code 1234567890.xml})
     * @param accessKey    clave de acceso (para rastreo de eventos)
     * @param documentType tipo de documento legible (para rastreo de eventos)
     * @throws EmailSendException si la validación falla o el envío falla
     */
    public static void sendDocumentDelivery(String apiKey, String fromEmail, String fromName,
            String to, String subject, String htmlBody,
            byte[] ridePdf, String rideName,
            byte[] authorizedXml, String xmlName,
            String accessKey, String documentType) {

        var attachments = buildDocumentAttachments(ridePdf, rideName, authorizedXml, xmlName);
        validateAndSend(apiKey, fromEmail, fromName, to, subject, htmlBody, attachments,
                Map.of("access_key", accessKey != null ? accessKey : "",
                       "document_type", documentType != null ? documentType : ""));
    }

    // ── Implementación ───────────────────────────────────────────────────────

    private static void validateAndSend(String apiKey, String fromEmail, String fromName,
            String to, String subject, String htmlBody,
            List<PlunkClient.Attachment> attachments,
            Map<String, String> trackData) {

        // 1. Validar dirección destino
        var verify = PlunkClient.verify(apiKey, to);

        if (!verify.valid() || !verify.hasMxRecords() || !verify.domainExists()) {
            log.warnf("Plunk verify rejected email | to=%s valid=%b hasMx=%b domainExists=%b reasons=%s",
                    to, verify.valid(), verify.hasMxRecords(), verify.domainExists(), verify.reasons());
            PlunkClient.track(apiKey, to, "email.delivery_aborted",
                    Map.of("reason", "invalid_destination",
                           "details", String.join(";", verify.reasons())));
            throw new EmailSendException(
                    "Email address rejected by validation: " + to
                    + " reasons=" + verify.reasons());
        }

        if (verify.isDisposable()) {
            log.warnf("Plunk verify: disposable email detected | to=%s — sending anyway", to);
        }
        if (verify.isTypo()) {
            log.warnf("Plunk verify: possible typo detected | to=%s — sending anyway", to);
        }

        // 2. Enviar
        var sender = new PlunkClient.SendRequest.SenderInfo(fromName, fromEmail);
        var request = new PlunkClient.SendRequest(to, sender, subject, htmlBody, attachments);
        PlunkClient.send(apiKey, request);

        // 3. Rastrear evento (soft-fail integrado en PlunkClient.track)
        if (trackData != null) {
            PlunkClient.track(apiKey, to, "document.sent", trackData);
        }
    }

    private static List<PlunkClient.Attachment> buildDocumentAttachments(
            byte[] ridePdf, String rideName,
            byte[] authorizedXml, String xmlName) {
        var list = new ArrayList<PlunkClient.Attachment>();
        if (ridePdf != null && ridePdf.length > 0 && rideName != null) {
            list.add(PlunkClient.Attachment.of(rideName, ridePdf, "application/pdf"));
        }
        if (authorizedXml != null && authorizedXml.length > 0 && xmlName != null) {
            list.add(PlunkClient.Attachment.of(xmlName, authorizedXml, "application/xml"));
        }
        return List.copyOf(list);
    }
}
