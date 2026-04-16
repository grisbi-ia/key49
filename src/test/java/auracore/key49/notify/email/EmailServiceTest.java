package auracore.key49.notify.email;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import auracore.key49.core.model.Tenant;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;

class EmailServiceTest {

    private static final String ACCESS_KEY = "2506202501099271531200110010020000000011234567813";

    private SmtpClientFactory smtpClientFactory;
    private Template template;
    private TemplateInstance templateInstance;
    private EmailService service;

    @BeforeEach
    void setUp() {
        smtpClientFactory = mock(SmtpClientFactory.class);
        template = mock(Template.class);
        templateInstance = mock(TemplateInstance.class);

        when(template.data(any(String.class), any())).thenReturn(templateInstance);
        when(templateInstance.render()).thenReturn("<html>test</html>");

        service = new EmailService();
        service.smtpClientFactory = smtpClientFactory;
        service.documentDeliveryTemplate = template;
        service.emailEnabled = true;
    }

    @Test
    void shouldThrowWhenTenantIsNull() {
        var data = createEmailData(List.of("test@test.com"), null, null);

        assertThrows(IllegalArgumentException.class,
                () -> service.sendDocumentDelivery(data, null));
    }

    @Test
    void shouldSkipWhenEmailDisabled() {
        service.emailEnabled = false;
        var tenant = tenantWithSmtp("smtp.example.com");
        var data = createEmailData(List.of("test@test.com"), null, null);

        service.sendDocumentDelivery(data, tenant);

        verify(smtpClientFactory, never()).getOrCreate(any());
    }

    @Test
    void shouldSkipWhenNoRecipientEmails() {
        var tenant = tenantWithSmtp("smtp.example.com");
        var data = createEmailData(List.of(), null, null);

        service.sendDocumentDelivery(data, tenant);

        verify(smtpClientFactory, never()).getOrCreate(any());
    }

    @Test
    void shouldSkipWhenSmtpHostNotConfigured() {
        var tenant = tenantWithSmtp(null);
        var data = createEmailData(List.of("test@test.com"), null, null);

        service.sendDocumentDelivery(data, tenant);

        verify(smtpClientFactory, never()).getOrCreate(any());
    }

    @Test
    void shouldSkipWhenSmtpHostIsBlank() {
        var tenant = tenantWithSmtp("   ");
        var data = createEmailData(List.of("test@test.com"), null, null);

        service.sendDocumentDelivery(data, tenant);

        verify(smtpClientFactory, never()).getOrCreate(any());
    }

    @Test
    void shouldThrowWhenPlunkApiKeyNotConfigured() {
        var tenant = new Tenant();
        tenant.id = UUID.randomUUID();
        tenant.emailProvider = "plunk";
        tenant.plunkApiKeyEnc = null;

        var data = createEmailData(List.of("test@test.com"), null, null);

        assertThrows(EmailSendException.class,
                () -> service.sendDocumentDelivery(data, tenant));
    }

    @Test
    void shouldThrowWhenSmtpFromNotConfiguredForPlunk() {
        var tenant = new Tenant();
        tenant.id = UUID.randomUUID();
        tenant.emailProvider = "plunk";
        tenant.plunkApiKeyEnc = "somekey".getBytes(StandardCharsets.UTF_8);
        tenant.smtpFrom = null;
        // masterKeyBase64 not set → will fail on decrypt, but smtpFrom check runs after decrypt
        // We test smtpFrom validation via SMTP path instead
        var smtpTenant = tenantWithSmtp("smtp.example.com");
        smtpTenant.smtpFrom = null;

        var data = createEmailData(List.of("test@test.com"), null, null);

        // SMTP path: smtpHost configured but smtpFrom missing → EmailSendException
        // SmtpClientFactory.getOrCreate will be called before smtpFrom check
        // Use a session stub to get past factory
        when(smtpClientFactory.getOrCreate(any())).thenReturn(
                new SmtpClientFactory.TenantSmtpSession(null, null));

        assertThrows(EmailSendException.class,
                () -> service.sendDocumentDelivery(data, smtpTenant));
    }

    // ── Helpers ──

    private static Tenant tenantWithSmtp(String host) {
        var t = new Tenant();
        t.id = UUID.randomUUID();
        t.emailProvider = "smtp";
        t.smtpHost = host;
        t.smtpPort = 587;
        return t;
    }

    private static EmailData createEmailData(List<String> emails, byte[] ridePdf, byte[] authorizedXml) {
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
