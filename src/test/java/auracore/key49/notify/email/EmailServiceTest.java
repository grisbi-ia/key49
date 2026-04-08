package auracore.key49.notify.email;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.Template;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmailServiceTest {

    private static final String ACCESS_KEY = "2506202501099271531200110010020000000011234567813";

    private Mailer mailer;
    private Template template;
    private TemplateInstance templateInstance;
    private EmailService service;

    @BeforeEach
    void setUp() {
        mailer = mock(Mailer.class);
        template = mock(Template.class);
        templateInstance = mock(TemplateInstance.class);

        when(template.data(any(String.class), any())).thenReturn(templateInstance);
        when(templateInstance.render()).thenReturn("<html>test</html>");
        doNothing().when(mailer).send(any(Mail.class));

        service = new EmailService();
        service.mailer = mailer;
        service.documentDeliveryTemplate = template;
        service.fromAddress = "facturacion@key49.ec";
        service.emailEnabled = true;
    }

    @Test
    void shouldSendEmailWithAttachments() {
        var data = createEmailData(List.of("cliente@test.com"),
                "RIDE PDF bytes".getBytes(StandardCharsets.UTF_8),
                "<xml>authorized</xml>".getBytes(StandardCharsets.UTF_8));

        service.sendDocumentDelivery(data);

        var captor = ArgumentCaptor.forClass(Mail.class);
        verify(mailer).send(captor.capture());

        var mail = captor.getValue();
        assertTrue(mail.getTo().contains("cliente@test.com"));
        assertTrue(mail.getHtml().contains("test"));
        assertTrue(mail.getSubject().contains("Factura"));
        assertTrue(mail.getSubject().contains("001-001-000000042"));
        assertTrue(mail.getAttachments().size() >= 2);
    }

    @Test
    void shouldSendToFirstRecipientWithCc() {
        var data = createEmailData(
                List.of("main@test.com", "cc1@test.com", "cc2@test.com"),
                null, null);

        service.sendDocumentDelivery(data);

        var captor = ArgumentCaptor.forClass(Mail.class);
        verify(mailer).send(captor.capture());

        var mail = captor.getValue();
        assertTrue(mail.getTo().contains("main@test.com"));
        assertTrue(mail.getCc().contains("cc1@test.com"));
        assertTrue(mail.getCc().contains("cc2@test.com"));
    }

    @Test
    void shouldSkipWhenEmailDisabled() {
        service.emailEnabled = false;
        var data = createEmailData(List.of("test@test.com"), null, null);

        service.sendDocumentDelivery(data);

        verify(mailer, never()).send(any());
    }

    @Test
    void shouldSkipWhenNoRecipientEmails() {
        var data = createEmailData(List.of(), null, null);

        service.sendDocumentDelivery(data);

        verify(mailer, never()).send(any());
    }

    @Test
    void shouldSendWithoutAttachmentsWhenNull() {
        var data = createEmailData(List.of("test@test.com"), null, null);

        service.sendDocumentDelivery(data);

        var captor = ArgumentCaptor.forClass(Mail.class);
        verify(mailer).send(captor.capture());

        var mail = captor.getValue();
        assertTrue(mail.getAttachments().isEmpty());
    }

    @Test
    void shouldAttachOnlyRideWhenXmlIsNull() {
        var data = createEmailData(List.of("test@test.com"),
                "PDF content".getBytes(StandardCharsets.UTF_8), null);

        service.sendDocumentDelivery(data);

        var captor = ArgumentCaptor.forClass(Mail.class);
        verify(mailer).send(captor.capture());

        var mail = captor.getValue();
        var attachments = mail.getAttachments();
        assertTrue(attachments.stream().anyMatch(a ->
                a.getName().endsWith(".pdf")));
    }

    @Test
    void shouldAttachOnlyXmlWhenRideIsNull() {
        var data = createEmailData(List.of("test@test.com"),
                null, "<xml>data</xml>".getBytes(StandardCharsets.UTF_8));

        service.sendDocumentDelivery(data);

        var captor = ArgumentCaptor.forClass(Mail.class);
        verify(mailer).send(captor.capture());

        var mail = captor.getValue();
        var attachments = mail.getAttachments();
        assertTrue(attachments.stream().anyMatch(a ->
                a.getName().endsWith(".xml")));
    }

    @Test
    void shouldWrapMailerFailureAsEmailSendException() {
        doThrow(new RuntimeException("SMTP error")).when(mailer).send(any(Mail.class));

        var data = createEmailData(List.of("test@test.com"), null, null);

        assertThrows(EmailSendException.class, () ->
                service.sendDocumentDelivery(data));
    }

    @Test
    void shouldSetFromAddressWithIssuerName() {
        var data = createEmailData(List.of("test@test.com"), null, null);

        service.sendDocumentDelivery(data);

        var captor = ArgumentCaptor.forClass(Mail.class);
        verify(mailer).send(captor.capture());

        var mail = captor.getValue();
        assertTrue(mail.getFrom().contains("Empresa Test S.A."));
        assertTrue(mail.getFrom().contains("facturacion@key49.ec"));
    }

    @Test
    void shouldRenderTemplateWithData() {
        var data = createEmailData(List.of("test@test.com"), null, null);

        service.sendDocumentDelivery(data);

        verify(template).data("data", data);
        verify(templateInstance).render();
    }

    @Test
    void shouldSkipEmptyByteArrayAttachments() {
        var data = createEmailData(List.of("test@test.com"),
                new byte[0], new byte[0]);

        service.sendDocumentDelivery(data);

        var captor = ArgumentCaptor.forClass(Mail.class);
        verify(mailer).send(captor.capture());

        assertTrue(captor.getValue().getAttachments().isEmpty());
    }

    private EmailData createEmailData(List<String> emails, byte[] ridePdf, byte[] authorizedXml) {
        return new EmailData(
                "Empresa Test S.A.",
                "0990012345001",
                "Cliente Test",
                emails,
                "Factura",
                "001-001-000000042",
                ACCESS_KEY,
                LocalDate.of(2025, 6, 20),
                BigDecimal.valueOf(115.50),
                "DOLAR",
                ridePdf,
                authorizedXml
        );
    }
}
