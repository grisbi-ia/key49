package auracore.key49.notify.email;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Servicio de envío de emails para notificación de comprobantes electrónicos.
 *
 * <p>Envía el RIDE (PDF) y XML autorizado como adjuntos al receptor del comprobante.
 * Soporta múltiples destinatarios separados por {@code ;} en el campo {@code recipient_email}.</p>
 */
@ApplicationScoped
public class EmailService {

    private static final Logger log = Logger.getLogger(EmailService.class);

    @Inject
    ReactiveMailer mailer;

    @Location("document-delivery.html")
    Template documentDeliveryTemplate;

    @ConfigProperty(name = "key49.email.from", defaultValue = "facturacion@key49.ec")
    String fromAddress;

    @ConfigProperty(name = "key49.email.enabled", defaultValue = "true")
    boolean emailEnabled;

    /**
     * Envía el email de entrega de comprobante electrónico.
     *
     * @param data datos del email (emisor, receptor, adjuntos)
     * @return Uni<Void> que completa cuando el email ha sido enviado
     * @throws EmailSendException si falla el envío
     */
    public Uni<Void> sendDocumentDelivery(EmailData data) {
        if (!emailEnabled) {
            log.infof("Email disabled, skipping delivery for document %s", data.accessKey());
            return Uni.createFrom().voidItem();
        }

        if (data.recipientEmails().isEmpty()) {
            log.warnf("No recipient emails for document %s, skipping", data.accessKey());
            return Uni.createFrom().voidItem();
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

        return mailer.send(mail)
                .onFailure().transform(error -> {
                    log.errorf(error, "Failed to send email for document %s", data.accessKey());
                    return new EmailSendException(
                            "Failed to send email for document " + data.accessKey(), error);
                });
    }
}
