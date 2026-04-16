package auracore.key49.notify.email;

import java.time.Duration;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Servicio de envío de emails de la plataforma Key49 (sistema).
 *
 * <p>Unifica los dos canales de salida para emails internos de la plataforma
 * (verificación de email, recuperación de contraseña, alertas de plan):</p>
 * <ul>
 *   <li>{@link EmailProvider#SMTP} — SMTP compartido de Key49 vía
 *       Quarkus {@link ReactiveMailer}. Canal por defecto.</li>
 *   <li>{@link EmailProvider#PLUNK} — API REST de Plunk. Requiere
 *       {@code key49.plunk.platform-api-key} y
 *       {@code key49.plunk.platform-from-email} configurados.</li>
 * </ul>
 *
 * <p>Los servicios que antes inyectaban {@link ReactiveMailer} directamente
 * deben inyectar este servicio en su lugar.</p>
 */
@ApplicationScoped
public class PlatformEmailService {

    private static final Logger log = Logger.getLogger(PlatformEmailService.class);

    @Inject
    ReactiveMailer reactiveMailer;

    @ConfigProperty(name = "key49.email.from", defaultValue = "facturacion@key49.ec")
    String smtpFrom;

    @ConfigProperty(name = "key49.email.from-name", defaultValue = "Key49")
    String smtpFromName;

    @ConfigProperty(name = "key49.email.send-timeout-seconds", defaultValue = "120")
    int sendTimeoutSeconds;

    @ConfigProperty(name = "key49.email.enabled", defaultValue = "true")
    boolean emailEnabled;

    @ConfigProperty(name = "key49.platform.email-provider", defaultValue = "smtp")
    String providerConfig;

    @ConfigProperty(name = "key49.plunk.platform-api-key")
    Optional<String> plunkApiKey;

    @ConfigProperty(name = "key49.plunk.platform-from-email")
    Optional<String> plunkFromEmail;

    @ConfigProperty(name = "key49.plunk.platform-from-name", defaultValue = "Key49")
    String plunkFromName;

    /**
     * Envía un email HTML de plataforma al destinatario.
     *
     * <p>Usa el canal configurado en {@code key49.platform.email-provider}
     * (smtp o plunk). Si el email está deshabilitado globalmente
     * ({@code key49.email.enabled=false}), la llamada es no-op.</p>
     *
     * @param to       email del destinatario
     * @param subject  asunto
     * @param htmlBody cuerpo HTML
     * @throws EmailSendException si el envío falla
     */
    public void sendHtml(String to, String subject, String htmlBody) {
        if (!emailEnabled) {
            log.debugf("Platform email disabled, skipping | to=%s subject=%s", to, subject);
            return;
        }

        var provider = resolveProvider();
        log.infof("Sending platform email | provider=%s to=%s subject=%s", provider, to, subject);

        switch (provider) {
            case PLUNK -> sendViaPlunk(to, subject, htmlBody);
            case SMTP  -> sendViaSmtp(to, subject, htmlBody);
        }
    }

    /**
     * Envía un email de texto plano de plataforma (alertas simples).
     *
     * <p>En canal Plunk, el texto se envuelve en HTML básico para cumplir
     * con el campo {@code body} que acepta HTML.</p>
     *
     * @param to      email del destinatario
     * @param subject asunto
     * @param text    cuerpo en texto plano
     * @throws EmailSendException si el envío falla
     */
    public void sendText(String to, String subject, String text) {
        var html = "<pre style=\"font-family:sans-serif;white-space:pre-wrap\">"
                + escapeHtml(text) + "</pre>";
        sendHtml(to, subject, html);
    }

    // ── Implementación ───────────────────────────────────────────────────────

    private void sendViaPlunk(String to, String subject, String htmlBody) {
        var apiKey = plunkApiKey.orElseThrow(() ->
                new EmailSendException("Plunk platform-api-key not configured"));
        var fromEmail = plunkFromEmail.orElseThrow(() ->
                new EmailSendException("Plunk platform-from-email not configured"));

        PlunkEmailSender.sendPlatform(apiKey, fromEmail, plunkFromName, to, subject, htmlBody);
    }

    private void sendViaSmtp(String to, String subject, String htmlBody) {
        var mail = Mail.withHtml(to, subject, htmlBody);
        mail.setFrom(smtpFromName + " <" + smtpFrom + ">");
        try {
            reactiveMailer.send(mail).await().atMost(Duration.ofSeconds(sendTimeoutSeconds));
        } catch (Exception e) {
            throw new EmailSendException("Platform SMTP send failed to=" + to, e);
        }
    }

    private EmailProvider resolveProvider() {
        if ("plunk".equalsIgnoreCase(providerConfig)) {
            return EmailProvider.PLUNK;
        }
        return EmailProvider.SMTP;
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
