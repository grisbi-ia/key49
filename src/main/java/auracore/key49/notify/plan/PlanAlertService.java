package auracore.key49.notify.plan;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * Servicio de alertas de plan y cuota. Dispara webhooks y emails cuando:
 * <ul>
 * <li>{@code plan.quota_warning} — uso alcanza el 80% de la cuota</li>
 * <li>{@code plan.quota_exhausted} — cuota agotada completamente</li>
 * <li>{@code plan.expiring} — plan vence en 7 días o menos</li>
 * </ul>
 *
 * <p>
 * Las alertas de cuota se disparan desde {@code QuotaService} durante la
 * reserva. Las alertas de expiración se verifican con un job diario a las 08:00
 * ECT.</p>
 */
@ApplicationScoped
public class PlanAlertService {

    private static final Logger log = Logger.getLogger(PlanAlertService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    static final double QUOTA_WARNING_THRESHOLD = 0.80;
    static final int PLAN_EXPIRING_DAYS = 7;

    /**
     * Lista de eventos disparados, observable para tests. Thread-safe.
     */
    private final List<String> firedAlerts = Collections.synchronizedList(new ArrayList<>());

    List<String> getFiredAlerts() {
        return firedAlerts;
    }

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
    int connectTimeoutMs;

    @ConfigProperty(name = "key49.webhook.read-timeout-ms", defaultValue = "10000")
    int readTimeoutMs;

    @ConfigProperty(name = "key49.webhook.ssrf-validation", defaultValue = "true")
    boolean ssrfValidation;

    // ── Quota threshold detection ───────────────────────────────────────────
    /**
     * Verifica si el umbral de 80% fue cruzado por esta reserva.
     */
    boolean crossedWarningThreshold(int usedBefore, int usedAfter, int quota) {
        int threshold = (int) (quota * QUOTA_WARNING_THRESHOLD);
        return usedBefore < threshold && usedAfter >= threshold;
    }

    /**
     * Verifica si la cuota se agotó exactamente con esta reserva.
     */
    boolean justExhausted(int usedAfter, int quota) {
        return usedAfter >= quota;
    }

    // ── Public API ──────────────────────────────────────────────────────────
    /**
     * Evalúa umbrales de cuota y dispara alertas si corresponde. Llamado desde
     * {@code QuotaService} después de una reserva exitosa.
     *
     * @param tenantId UUID del tenant
     * @param usedBefore documentos usados antes de la reserva
     * @param usedAfter documentos usados después de la reserva
     * @param quota cuota total del plan
     */
    public void checkQuotaThresholds(UUID tenantId, int usedBefore, int usedAfter, int quota) {
        boolean warning = crossedWarningThreshold(usedBefore, usedAfter, quota);
        boolean exhausted = justExhausted(usedAfter, quota);

        if (!warning && !exhausted) {
            return;
        }

        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(
                "SELECT legal_name, ruc, reply_email, webhook_url, webhook_secret "
                + "FROM public.tenants WHERE tenant_id = ?::uuid")) {
            ps.setString(1, tenantId.toString());
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }

                var legalName = rs.getString("legal_name");
                var ruc = rs.getString("ruc");
                var email = rs.getString("reply_email");
                var webhookUrl = rs.getString("webhook_url");
                var webhookSecret = rs.getString("webhook_secret");
                int usagePercent = quota > 0 ? (usedAfter * 100 / quota) : 100;

                if (warning) {
                    var payload = quotaPayload("plan.quota_warning", tenantId, legalName,
                            usedAfter, quota, usagePercent);
                    var emailBody = quotaWarningEmail(legalName, ruc, usedAfter, quota, usagePercent);
                    fireAlert("plan.quota_warning", webhookUrl, webhookSecret, payload,
                            email, "Key49 — Alerta de cuota: %d%% utilizado".formatted(usagePercent),
                            emailBody);
                    log.infof("Quota warning alert fired | tenant=%s usage=%d/%d (%d%%)",
                            tenantId, usedAfter, quota, usagePercent);
                }

                if (exhausted) {
                    var payload = quotaPayload("plan.quota_exhausted", tenantId, legalName,
                            usedAfter, quota, 100);
                    var emailBody = quotaExhaustedEmail(legalName, ruc, quota);
                    fireAlert("plan.quota_exhausted", webhookUrl, webhookSecret, payload,
                            email, "Key49 — Cuota de documentos agotada", emailBody);
                    log.infof("Quota exhausted alert fired | tenant=%s usage=%d/%d",
                            tenantId, usedAfter, quota);
                }
            }
        } catch (Exception e) {
            log.errorf(e, "Failed to check/fire quota alerts for tenant=%s", tenantId);
        }
    }

    // ── Scheduled job ───────────────────────────────────────────────────────
    /**
     * Job diario (08:00 ECT) que verifica planes próximos a vencer y notifica
     * al tenant con webhook {@code plan.expiring} y email.
     */
    @Scheduled(cron = "0 0 8 * * ?", timeZone = "America/Guayaquil", identity = "plan-expiration-check")
    void checkExpiringPlans() {
        log.info("Checking for expiring plans...");
        var threshold = Instant.now().plus(PLAN_EXPIRING_DAYS, ChronoUnit.DAYS);

        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(
                "SELECT tenant_id, legal_name, ruc, reply_email, plan_expires_at, "
                + "webhook_url, webhook_secret "
                + "FROM public.tenants "
                + "WHERE status = 'active' AND plan_expires_at IS NOT NULL "
                + "AND plan_expires_at <= ? AND plan_expires_at > now() "
                + "ORDER BY plan_expires_at ASC")) {

            ps.setTimestamp(1, Timestamp.from(threshold));
            try (var rs = ps.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    var tenantId = rs.getObject("tenant_id", UUID.class);
                    var legalName = rs.getString("legal_name");
                    var ruc = rs.getString("ruc");
                    var email = rs.getString("reply_email");
                    var expiresAt = rs.getTimestamp("plan_expires_at").toInstant();
                    var webhookUrl = rs.getString("webhook_url");
                    var webhookSecret = rs.getString("webhook_secret");
                    var daysLeft = ChronoUnit.DAYS.between(Instant.now(), expiresAt);

                    log.warnf("Plan expiring | tenant=%s name=%s daysLeft=%d",
                            tenantId, legalName, daysLeft);

                    var payload = expiringPayload(tenantId, legalName, daysLeft, expiresAt);
                    var emailBody = planExpiringEmail(legalName, ruc, daysLeft);
                    fireAlert("plan.expiring", webhookUrl, webhookSecret, payload,
                            email, "Key49 — Plan próximo a vencer (%d días)".formatted(daysLeft),
                            emailBody);
                }
                log.infof("Plan expiration check complete: %d plan(s) expiring within %d days",
                        count, PLAN_EXPIRING_DAYS);
            }
        } catch (Exception e) {
            log.errorf(e, "Error checking plan expirations");
        }
    }

    // ── Alert dispatch ──────────────────────────────────────────────────────
    void fireAlert(String event, String webhookUrl, String webhookSecret,
            String webhookPayload, String email, String emailSubject, String emailBody) {
        firedAlerts.add(event);

        if (webhookEnabled && webhookUrl != null && !webhookUrl.isBlank()) {
            dispatchWebhook(event, webhookUrl, webhookSecret, webhookPayload);
        }

        if (emailEnabled && email != null && !email.isBlank()) {
            sendEmail(email, emailSubject, emailBody);
        }
    }

    void dispatchWebhook(String event, String webhookUrl, String webhookSecret,
            String payload) {
        try {
            if (ssrfValidation) {
                WebhookUrlValidator.validate(webhookUrl);
            }

            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("Content-Type", "application/json")
                    .header("X-Key49-Event", event)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));

            if (webhookSecret != null && !webhookSecret.isBlank()) {
                var signature = computeHmac(payload, webhookSecret);
                requestBuilder.header("X-Key49-Signature", "sha256=" + signature);
            }

            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            var response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            log.infof("Plan alert webhook dispatched | event=%s url=%s status=%d",
                    event, webhookUrl, response.statusCode());
        } catch (Exception e) {
            log.errorf(e, "Failed to dispatch plan alert webhook | event=%s url=%s", event, webhookUrl);
        }
    }

    private void sendEmail(String to, String subject, String body) {
        try {
            mailer.send(Mail.withText(to, subject, body)
                    .setFrom("Key49 <" + fromAddress + ">"));
            log.infof("Plan alert email sent | to=%s subject=%s", to, subject);
        } catch (Exception e) {
            log.errorf(e, "Failed to send plan alert email | to=%s", to);
        }
    }

    // ── Payload builders ────────────────────────────────────────────────────
    String quotaPayload(String event, UUID tenantId, String legalName,
            int used, int quota, int usagePercent) {
        return """
                {"event":"%s","tenant_id":"%s","legal_name":"%s",\
                "documents_used":%d,"document_quota":%d,"usage_percent":%d,\
                "timestamp":"%s"}\
                """.formatted(event, tenantId, escapeJson(legalName),
                used, quota, usagePercent, Instant.now().toString());
    }

    String expiringPayload(UUID tenantId, String legalName, long daysLeft, Instant expiresAt) {
        return """
                {"event":"plan.expiring","tenant_id":"%s","legal_name":"%s",\
                "days_remaining":%d,"plan_expires_at":"%s",\
                "timestamp":"%s"}\
                """.formatted(tenantId, escapeJson(legalName),
                daysLeft, expiresAt.toString(), Instant.now().toString());
    }

    // ── Email body builders ─────────────────────────────────────────────────
    private String quotaWarningEmail(String legalName, String ruc, int used, int quota,
            int usagePercent) {
        return """
                Estimado/a contribuyente,

                La cuota de documentos electrónicos de su cuenta en Key49 ha alcanzado el %d%% de uso.

                RUC: %s
                Razón Social: %s
                Documentos emitidos: %d de %d

                Le recomendamos actualizar su plan antes de que se agote la cuota para evitar \
                interrupciones en la emisión de comprobantes electrónicos.

                Atentamente,
                Key49 - Facturación Electrónica
                """.formatted(usagePercent, ruc, legalName, used, quota);
    }

    private String quotaExhaustedEmail(String legalName, String ruc, int quota) {
        return """
                Estimado/a contribuyente,

                La cuota de documentos electrónicos de su cuenta en Key49 se ha agotado.

                RUC: %s
                Razón Social: %s
                Documentos emitidos: %d de %d (100%%)

                No podrá emitir nuevos comprobantes electrónicos hasta que renueve o actualice su plan.

                Atentamente,
                Key49 - Facturación Electrónica
                """.formatted(ruc, legalName, quota, quota);
    }

    private String planExpiringEmail(String legalName, String ruc, long daysLeft) {
        return """
                Estimado/a contribuyente,

                El plan de su cuenta en Key49 vence en %d día(s).

                RUC: %s
                Razón Social: %s

                Por favor renueve su plan para evitar interrupciones en la emisión de \
                comprobantes electrónicos.

                Atentamente,
                Key49 - Facturación Electrónica
                """.formatted(daysLeft, ruc, legalName);
    }

    // ── Utilities ───────────────────────────────────────────────────────────
    static String computeHmac(String body, String secret) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            var keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            var hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.errorf(e, "Failed to compute HMAC signature");
            return "";
        }
    }

    static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
