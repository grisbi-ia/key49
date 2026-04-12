package auracore.key49.notify.email;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import auracore.key49.core.model.Tenant;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.vertx.ext.mail.MailMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Servicio de envío de emails para notificación de comprobantes electrónicos.
 *
 * <p>
 * Envía el RIDE (PDF) y XML autorizado como adjuntos al receptor del
 * comprobante. Soporta múltiples destinatarios separados por {@code ;} en el
 * campo {@code recipient_email}.</p>
 *
 * <p>
 * Si el tenant tiene SMTP propio ({@code smtpEnabled = true}), se envía a
 * través del {@link SmtpClientFactory}. Si falla 3 veces, hace fallback al SMTP
 * compartido de Key49. Si {@code smtpEnabled = false}, usa el mailer global de
 * Quarkus.</p>
 */
@ApplicationScoped
public class EmailService {

    private static final Logger log = Logger.getLogger(EmailService.class);
    private static final int TENANT_SMTP_MAX_RETRIES = 3;

    @Inject
    ReactiveMailer reactiveMailer;

    @Inject
    SmtpClientFactory smtpClientFactory;

    @Location("document-delivery.html")
    Template documentDeliveryTemplate;

    @ConfigProperty(name = "key49.email.from", defaultValue = "facturacion@key49.ec")
    String fromAddress;

    @ConfigProperty(name = "key49.email.enabled", defaultValue = "true")
    boolean emailEnabled;

    @ConfigProperty(name = "key49.email.send-timeout-seconds", defaultValue = "120")
    int sendTimeoutSeconds;

    /**
     * Envía el email de entrega de comprobante electrónico.
     *
     * <p>
     * Si el tenant tiene SMTP propio habilitado, intenta enviar por ese canal.
     * Si falla 3 veces, hace fallback al SMTP compartido de Key49.</p>
     *
     * @param data datos del email (emisor, receptor, adjuntos)
     * @param tenant el tenant emisor (determina qué SMTP usar)
     * @throws EmailSendException si falla el envío por ambos canales
     */
    public void sendDocumentDelivery(EmailData data, Tenant tenant) {
        if (!emailEnabled) {
            log.infof("Email disabled, skipping delivery for document %s", data.accessKey());
            return;
        }

        if (data.recipientEmails().isEmpty()) {
            log.warnf("No recipient emails for document %s, skipping", data.accessKey());
            return;
        }

        if (tenant != null && tenant.smtpEnabled) {
            if (sendViaTenantSmtp(data, tenant)) {
                return;
            }
            log.warnf("Tenant SMTP failed after %d retries for document %s, falling back to shared SMTP",
                    TENANT_SMTP_MAX_RETRIES, data.accessKey());
        }

        sendViaSharedSmtp(data);
    }

    /**
     * Envía el email de entrega usando el SMTP compartido de Key49 (sin
     * tenant).
     *
     * @param data datos del email
     * @throws EmailSendException si falla el envío
     */
    public void sendDocumentDelivery(EmailData data) {
        sendDocumentDelivery(data, null);
    }

    /**
     * Intenta enviar email via SMTP del tenant con reintentos.
     *
     * @return true si el envío fue exitoso, false si se agotaron los reintentos
     */
    private boolean sendViaTenantSmtp(EmailData data, Tenant tenant) {
        var client = smtpClientFactory.getOrCreate(tenant);
        var from = tenant.smtpFrom != null ? tenant.smtpFrom : fromAddress;
        var senderFrom = data.issuerName() + " <" + from + ">";

        var message = new MailMessage()
                .setFrom(senderFrom)
                .setTo(java.util.List.of(data.recipientEmails().getFirst()))
                .setSubject(buildSubject(data))
                .setHtml(renderHtml(data));

        // CC recipients
        if (data.recipientEmails().size() > 1) {
            message.setCc(data.recipientEmails().subList(1, data.recipientEmails().size()));
        }

        // Attachments
        var attachments = new java.util.ArrayList<io.vertx.ext.mail.MailAttachment>();
        if (data.ridePdf() != null && data.ridePdf().length > 0) {
            attachments.add(io.vertx.ext.mail.MailAttachment.create()
                    .setName(data.rideFilename())
                    .setContentType("application/pdf")
                    .setData(io.vertx.core.buffer.Buffer.buffer(data.ridePdf())));
        }
        if (data.authorizedXml() != null && data.authorizedXml().length > 0) {
            attachments.add(io.vertx.ext.mail.MailAttachment.create()
                    .setName(data.xmlFilename())
                    .setContentType("application/xml")
                    .setData(io.vertx.core.buffer.Buffer.buffer(data.authorizedXml())));
        }
        if (!attachments.isEmpty()) {
            message.setAttachment(attachments);
        }

        for (int attempt = 1; attempt <= TENANT_SMTP_MAX_RETRIES; attempt++) {
            try {
                log.infof("Sending via tenant SMTP (%s:%d) attempt %d/%d: document=%s, to=%s",
                        tenant.smtpHost, tenant.smtpPort, attempt, TENANT_SMTP_MAX_RETRIES,
                        data.accessKey(), data.recipientEmails().getFirst());
                client.sendMail(message)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(sendTimeoutSeconds, TimeUnit.SECONDS);
                return true;
            } catch (Exception e) {
                log.warnf(e, "Tenant SMTP send attempt %d/%d failed for document %s",
                        attempt, TENANT_SMTP_MAX_RETRIES, data.accessKey());
            }
        }
        return false;
    }

    /**
     * Envía email via SMTP compartido de Key49 (mailer global de Quarkus).
     */
    private void sendViaSharedSmtp(EmailData data) {
        var htmlBody = renderHtml(data);
        var subject = buildSubject(data);

        var mail = Mail.withHtml(
                data.recipientEmails().getFirst(),
                subject,
                htmlBody
        );

        for (int i = 1; i < data.recipientEmails().size(); i++) {
            mail.addCc(data.recipientEmails().get(i));
        }

        mail.setFrom(data.issuerName() + " <" + fromAddress + ">");

        if (data.ridePdf() != null && data.ridePdf().length > 0) {
            mail.addAttachment(data.rideFilename(), data.ridePdf(), "application/pdf");
        }

        if (data.authorizedXml() != null && data.authorizedXml().length > 0) {
            mail.addAttachment(data.xmlFilename(), data.authorizedXml(), "application/xml");
        }

        log.infof("Sending via shared SMTP: document=%s, to=%s, cc=%d",
                data.accessKey(),
                data.recipientEmails().getFirst(),
                data.recipientEmails().size() - 1);

        try {
            reactiveMailer.send(mail)
                    .await().atMost(Duration.ofSeconds(sendTimeoutSeconds));
        } catch (Exception error) {
            log.errorf(error, "Failed to send email for document %s", data.accessKey());
            throw new EmailSendException(
                    "Failed to send email for document " + data.accessKey(), error);
        }
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
