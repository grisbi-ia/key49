package auracore.key49.notify.email;

import java.time.Duration;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Servicio de envío de emails para notificación de comprobantes electrónicos.
 *
 * <p>
 * Envía el RIDE (PDF) y XML autorizado como adjuntos al receptor del
 * comprobante. Soporta múltiples destinatarios separados por {@code ;} en el
 * campo {@code recipient_email}.</p>
 */
@ApplicationScoped
public class EmailService {

    private static final Logger log = Logger.getLogger(EmailService.class);

    @Inject
    ReactiveMailer reactiveMailer;

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
     * @param data datos del email (emisor, receptor, adjuntos)
     * @throws EmailSendException si falla el envío
     */
    public void sendDocumentDelivery(EmailData data) {
        if (!emailEnabled) {
            log.infof("Email disabled, skipping delivery for document %s", data.accessKey());
            return;
        }

        if (data.recipientEmails().isEmpty()) {
            log.warnf("No recipient emails for document %s, skipping", data.accessKey());
            return;
        }

        var htmlBody = documentDeliveryTemplate
                .data("data", data)
                .render();

        var subject = "%s %s de %s".formatted(
                data.documentType(),
                data.documentNumber(),
                data.issuerName()
        );

        var mail = Mail.withHtml(
                data.recipientEmails().getFirst(),
                subject,
                htmlBody
        );

        // Add CC recipients if more than one
        for (int i = 1; i < data.recipientEmails().size(); i++) {
            mail.addCc(data.recipientEmails().get(i));
        }

        mail.setFrom(data.issuerName() + " <" + fromAddress + ">");

        // Attach RIDE PDF if available
        if (data.ridePdf() != null && data.ridePdf().length > 0) {
            mail.addAttachment(data.rideFilename(), data.ridePdf(), "application/pdf");
        }

        // Attach authorized XML if available
        if (data.authorizedXml() != null && data.authorizedXml().length > 0) {
            mail.addAttachment(data.xmlFilename(), data.authorizedXml(), "application/xml");
        }

        log.infof("Sending document delivery email: document=%s, to=%s, cc=%d",
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
}
