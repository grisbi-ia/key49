package auracore.key49.admin.health;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.scheduler.Scheduled;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

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
    PgPool pgPool;

    @Inject
    ReactiveMailer mailer;

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

        pgPool.preparedQuery(
                        "SELECT tenant_id, legal_name, ruc, reply_email, certificate_expiration, " +
                                "webhook_url, webhook_secret " +
                                "FROM public.tenants " +
                                "WHERE status = 'active' AND certificate_expiration IS NOT NULL " +
                                "AND certificate_expiration < $1 " +
                                "ORDER BY certificate_expiration ASC")
                .execute(Tuple.of(threshold.atOffset(ZoneOffset.UTC)))
                .subscribe().with(
                        rows -> {
                            int count = 0;
                            for (Row row : rows) {
                                count++;
                                var tenantId = row.getUUID("tenant_id").toString();
                                var legalName = row.getString("legal_name");
                                var ruc = row.getString("ruc");
                                var replyEmail = row.getString("reply_email");
                                var expiration = row.getOffsetDateTime("certificate_expiration").toInstant();
                                var webhookUrl = row.getString("webhook_url");
                                var webhookSecret = row.getString("webhook_secret");
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
                        },
                        error -> log.errorf(error, "Error checking certificate expirations")
                );
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

        mailer.send(Mail.withText(email, subject, body).setFrom("Key49 <" + fromAddress + ">"))
                .subscribe().with(
                        v -> log.infof("Certificate expiration email sent | to=%s daysLeft=%d", email, daysLeft),
                        e -> log.errorf(e, "Failed to send certificate expiration email | to=%s", email)
                );
    }

    private void sendExpirationWebhook(String tenantId, String legalName, long daysLeft,
                                       String webhookUrl, String webhookSecret) {
        var payload = """
                {"event":"certificate.expiring","tenant_id":"%s","legal_name":"%s","days_remaining":%d,"timestamp":"%s"}"""
                .formatted(tenantId, legalName, daysLeft, Instant.now().toString());

        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(webhookConnectTimeoutMs))
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

            client.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> log.infof("Certificate expiration webhook sent | tenant=%s status=%d", tenantId, response.statusCode()))
                    .exceptionally(e -> {
                        log.errorf(e, "Failed to send certificate expiration webhook | tenant=%s url=%s", tenantId, webhookUrl);
                        return null;
                    });
        } catch (Exception e) {
            log.errorf(e, "Failed to dispatch certificate expiration webhook | tenant=%s", tenantId);
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
