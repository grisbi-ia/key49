package auracore.key49.notify.email;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

import auracore.key49.core.model.Tenant;
import auracore.key49.signer.CertificateEncryptor;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.activation.DataHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.Message;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

/**
 * Servicio de envío de emails para notificación de comprobantes electrónicos.
 *
 * <p>Envía el RIDE (PDF) y XML autorizado como adjuntos al receptor del
 * comprobante. Soporta múltiples destinatarios separados por {@code ;} en el
 * campo {@code recipient_email}.</p>
 *
 * <p>Selección de canal según {@code tenant.emailProvider}:</p>
 * <ul>
 *   <li>{@code plunk} — API REST de Plunk. Requiere {@code smtp_from} configurado
 *       en el tenant. Sin fallback.</li>
 *   <li>{@code smtp} con {@code smtp_host} configurado — SMTP propio del tenant,
 *       con 3 reintentos. Sin fallback.</li>
 *   <li>{@code smtp} sin {@code smtp_host} — tenant no ha configurado email;
 *       se omite el envío con aviso en log.</li>
 * </ul>
 *
 * <p>Toda notificación de documentos requiere un tenant. No hay SMTP compartido
 * de plataforma en este flujo.</p>
 */
@ApplicationScoped
public class EmailService {

    private static final Logger log = Logger.getLogger(EmailService.class);
    private static final int TENANT_SMTP_MAX_RETRIES = 3;

    @Inject
    SmtpClientFactory smtpClientFactory;

    @Location("document-delivery.html")
    Template documentDeliveryTemplate;

    @ConfigProperty(name = "key49.email.enabled", defaultValue = "true")
    boolean emailEnabled;

    @ConfigProperty(name = "key49.master-key")
    Optional<String> masterKeyBase64;

    /**
     * Envía el email de entrega de comprobante electrónico.
     *
     * <p>El canal se selecciona según {@code tenant.emailProvider}. Si el tenant
     * no tiene {@code smtp_host} configurado, se omite el envío
     * con aviso en log. No se usa el SMTP compartido de Key49 en este flujo.</p>
     *
     * @param data   datos del email (emisor, receptor, adjuntos)
     * @param tenant el tenant emisor — obligatorio
     * @throws IllegalArgumentException si {@code tenant} es null
     * @throws EmailSendException       si falla el envío
     */
    public void sendDocumentDelivery(EmailData data, Tenant tenant) {
        if (tenant == null) {
            throw new IllegalArgumentException("sendDocumentDelivery requires a tenant");
        }

        if (!emailEnabled) {
            log.infof("Email disabled, skipping delivery for document %s", data.accessKey());
            return;
        }

        if (data.recipientEmails().isEmpty()) {
            log.warnf("No recipient emails for document %s, skipping", data.accessKey());
            return;
        }

        if ("plunk".equalsIgnoreCase(tenant.emailProvider)) {
            sendViaPlunk(data, tenant);
            return;
        }

        if (tenant.smtpHost == null || tenant.smtpHost.isBlank()) {
            log.warnf("Tenant SMTP not configured for tenant=%s, skipping email for document %s",
                    tenant.id, data.accessKey());
            return;
        }

        sendViaTenantSmtp(data, tenant);
    }

    /**
     * Envía email via SMTP del tenant con reintentos. Usa Jakarta Mail
     * (sincrónico) que funciona correctamente en worker threads.
     *
     * @throws EmailSendException si se agotan los reintentos
     */
    private void sendViaTenantSmtp(EmailData data, Tenant tenant) {
        if (tenant.smtpFrom == null || tenant.smtpFrom.isBlank()) {
            throw new EmailSendException(
                    "Tenant smtp_from is not configured | tenant=" + tenant.id
                    + " — configure the sender email in portal settings");
        }

        var smtpSession = smtpClientFactory.getOrCreate(tenant);
        var from = tenant.smtpFrom;

        Exception lastError = null;
        for (int attempt = 1; attempt <= TENANT_SMTP_MAX_RETRIES; attempt++) {
            try {
                log.infof("Sending via tenant SMTP (%s:%d) attempt %d/%d: document=%s, to=%s",
                        tenant.smtpHost, tenant.smtpPort, attempt, TENANT_SMTP_MAX_RETRIES,
                        data.accessKey(), data.recipientEmails().getFirst());

                var message = new MimeMessage(smtpSession.session());
                message.setFrom(new InternetAddress(from, data.issuerName()));
                message.setRecipient(Message.RecipientType.TO,
                        new InternetAddress(data.recipientEmails().getFirst()));

                for (int i = 1; i < data.recipientEmails().size(); i++) {
                    message.addRecipient(Message.RecipientType.CC,
                            new InternetAddress(data.recipientEmails().get(i)));
                }

                message.setSubject(buildSubject(data));

                var multipart = new MimeMultipart();

                // HTML body
                var htmlPart = new MimeBodyPart();
                htmlPart.setContent(renderHtml(data), "text/html; charset=UTF-8");
                multipart.addBodyPart(htmlPart);

                // PDF attachment
                if (data.ridePdf() != null && data.ridePdf().length > 0) {
                    var pdfPart = new MimeBodyPart();
                    pdfPart.setDataHandler(new DataHandler(
                            new ByteArrayDataSource(data.ridePdf(), "application/pdf")));
                    pdfPart.setFileName(data.rideFilename());
                    multipart.addBodyPart(pdfPart);
                }

                // XML attachment
                if (data.authorizedXml() != null && data.authorizedXml().length > 0) {
                    var xmlPart = new MimeBodyPart();
                    xmlPart.setDataHandler(new DataHandler(
                            new ByteArrayDataSource(data.authorizedXml(), "application/xml")));
                    xmlPart.setFileName(data.xmlFilename());
                    multipart.addBodyPart(xmlPart);
                }

                message.setContent(multipart);
                Transport.send(message);

                log.infof("Tenant SMTP send successful: document=%s, to=%s",
                        data.accessKey(), data.recipientEmails().getFirst());
                return;
            } catch (Exception e) {
                lastError = e;
                log.warnf(e, "Tenant SMTP send attempt %d/%d failed for document %s",
                        attempt, TENANT_SMTP_MAX_RETRIES, data.accessKey());
            }
        }
        throw new EmailSendException(
                "Tenant SMTP failed after " + TENANT_SMTP_MAX_RETRIES
                + " retries for document " + data.accessKey(), lastError);
    }

    /**
     * Envía el email de entrega vía Plunk. Descifra la API key del tenant,
     * valida el destino y envía con adjuntos PDF + XML.
     */
    private void sendViaPlunk(EmailData data, Tenant tenant) {
        if (tenant.plunkApiKeyEnc == null || tenant.plunkApiKeyEnc.length == 0) {
            throw new EmailSendException(
                    "Tenant email_provider=plunk but plunk_api_key_enc is not configured | tenant=" + tenant.id);
        }

        String apiKey;
        try {
            var masterKey = java.util.Base64.getDecoder()
                    .decode(masterKeyBase64.orElseThrow(() ->
                            new EmailSendException("Master key not configured")));
            apiKey = new String(CertificateEncryptor.decrypt(tenant.plunkApiKeyEnc, masterKey),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (EmailSendException e) {
            throw e;
        } catch (Exception e) {
            throw new EmailSendException(
                    "Failed to decrypt Plunk API key for tenant=" + tenant.id, e);
        }

        if (tenant.smtpFrom == null || tenant.smtpFrom.isBlank()) {
            throw new EmailSendException(
                    "Tenant email_provider=plunk but smtp_from is not configured | tenant=" + tenant.id
                    + " — configure the sender email in portal settings");
        }
        var fromEmail = tenant.smtpFrom;
        var fromName  = tenant.emailSenderName != null ? tenant.emailSenderName : tenant.legalName;
        var to        = data.recipientEmails().getFirst();
        var subject   = buildSubject(data);
        var htmlBody  = renderHtml(data);

        log.infof("Sending via Plunk | tenant=%s document=%s to=%s", tenant.id, data.accessKey(), to);

        PlunkEmailSender.sendDocumentDelivery(
                apiKey, fromEmail, fromName,
                to, subject, htmlBody,
                data.ridePdf(), data.rideFilename(),
                data.authorizedXml(), data.xmlFilename(),
                data.accessKey(), data.documentType());
    }

    private String renderHtml(EmailData data) {
        return documentDeliveryTemplate
                .data("data", data)
                .render();
    }

    private static String buildSubject(EmailData data) {
        return "%s %s de %s".formatted(
                data.documentType(),
                data.documentNumber(),
                data.issuerName());
    }
}
