package auracore.key49.admin.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import javax.sql.DataSource;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import auracore.key49.notify.webhook.WebhookUrlValidator;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Job programado que verifica certificados próximos a vencer y notifica
 * proactivamente.
 *
 * <p>
 * Ejecuta diariamente a las 08:00 ECT. Para cada tenant activo con certificado
 * que vence en menos de 30 días, envía email al tenant y webhook
 * {@code certificate.expiring}.</p>
 */
@ApplicationScoped
public class CertificateExpirationNotifier {

    private static final Logger log = Logger.getLogger(CertificateExpirationNotifier.class);
    private static final int WARNING_DAYS = 30;

    @Inject
    DataSource dataSource;

    @Inject
    Mailer mailer;

    @ConfigProperty(name = "key49.email.from", defaultValue = "facturacion@key49.ec")
    String fromAddress;

    @ConfigProperty(name = "key49.email.enabled", defaultValue = "true")
    boolean emailEnabled;

    @ConfigProperty(name = "key49.webhook.enabled", defaultValue = "true")
    boolean webhookEnabled;

    @ConfigProperty(name = "key49.webhook.connect-timeout-ms", defaultValue = "5000")
    int webhookConnectTimeoutMs;

    @ConfigProperty(name = "key49.webhook.read-timeout-ms", defaultValue = "10000")
    int webhookReadTimeoutMs;

    @Scheduled(cron = "0 0 8 * * ?", timeZone = "America/Guayaquil", identity = "certificate-expiration-check")
    void checkExpiringCertificates() {
        log.info("Checking for expiring certificates...");
        var threshold = Instant.now().plus(WARNING_DAYS, ChronoUnit.DAYS);

        try (var conn = dataSource.getConnection(); var stmt = conn.prepareStatement(
                "SELECT tenant_id, legal_name, ruc, reply_email, certificate_expiration, "
                + "webhook_url, webhook_secret "
                + "FROM public.tenants "
                + "WHERE status = 'active' AND certificate_expiration IS NOT NULL "
                + "AND certificate_expiration < ? "
                + "ORDER BY certificate_expiration ASC")) {

            stmt.setTimestamp(1, Timestamp.from(threshold));
            try (var rs = stmt.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    var tenantId = rs.getObject("tenant_id", java.util.UUID.class).toString();
                    var legalName = rs.getString("legal_name");
                    var ruc = rs.getString("ruc");
                    var replyEmail = rs.getString("reply_email");
                    var expiration = rs.getTimestamp("certificate_expiration").toInstant();
                    var webhookUrl = rs.getString("webhook_url");
                    var webhookSecret = rs.getString("webhook_secret");
                    var daysLeft = ChronoUnit.DAYS.between(Instant.now(), expiration);

                    log.warnf("Certificate expiring | tenant=%s name=%s daysLeft=%d", tenantId, legalName, daysLeft);

                    if (emailEnabled && replyEmail != null && !replyEmail.isBlank()) {
                        sendExpirationEmail(legalName, ruc, replyEmail, daysLeft);
                    }

                    if (webhookEnabled && webhookUrl != null && !webhookUrl.isBlank()) {
                        sendExpirationWebhook(tenantId, legalName, daysLeft, webhookUrl, webhookSecret);
                    }
                }
                log.infof("Certificate expiration check complete: %d certificate(s) expiring within %d days", count, WARNING_DAYS);
            }
        } catch (Exception e) {
            log.errorf(e, "Error checking certificate expirations");
        }
    }

    private void sendExpirationEmail(String legalName, String ruc, String email, long daysLeft) {
        var subject = "Key49 — Certificado de firma electrónica próximo a vencer";
        var body = """
                Estimado/a contribuyente,

                El certificado de firma electrónica asociado a su cuenta en Key49 vence en %d día(s).

                RUC: %s
                Razón Social: %s

                Por favor renueve su certificado .p12 y súbalo a la plataforma para evitar interrupciones en la emisión de comprobantes electrónicos.

                Atentamente,
                Key49 - Facturación Electrónica
                """.formatted(daysLeft, ruc, legalName);

        try {
            mailer.send(Mail.withText(email, subject, body).setFrom("Key49 <" + fromAddress + ">"));
            log.infof("Certificate expiration email sent | to=%s daysLeft=%d", email, daysLeft);
        } catch (Exception e) {
            log.errorf(e, "Failed to send certificate expiration email | to=%s", email);
        }
    }

    private void sendExpirationWebhook(String tenantId, String legalName, long daysLeft,
            String webhookUrl, String webhookSecret) {
        var payload = """
                {"event":"certificate.expiring","tenant_id":"%s","legal_name":"%s","days_remaining":%d,"timestamp":"%s"}"""
                .formatted(tenantId, legalName, daysLeft, Instant.now().toString());

        try {
            WebhookUrlValidator.validate(webhookUrl);
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(webhookConnectTimeoutMs))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofMillis(webhookReadTimeoutMs))
                    .header("Content-Type", "application/json")
                    .header("X-Key49-Event", "certificate.expiring")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));

            if (webhookSecret != null && !webhookSecret.isBlank()) {
                var signature = computeHmacSignature(payload, webhookSecret);
                requestBuilder.header("X-Key49-Signature", "sha256=" + signature);
            }

            var response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            log.infof("Certificate expiration webhook sent | tenant=%s status=%d", tenantId, response.statusCode());
        } catch (Exception e) {
            log.errorf(e, "Failed to send certificate expiration webhook | tenant=%s url=%s", tenantId, webhookUrl);
        }
    }

    private static String computeHmacSignature(String payload, String secret) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            var hash = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.errorf(e, "Failed to compute HMAC signature for webhook");
            return "";
        }
    }
}
