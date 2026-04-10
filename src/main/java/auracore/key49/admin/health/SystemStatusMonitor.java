package auracore.key49.admin.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import org.eclipse.microprofile.health.Liveness;

import auracore.key49.notify.webhook.WebhookUrlValidator;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Monitor de estado del sistema que detecta cambios y notifica a tenants por
 * webhook.
 *
 * <p>
 * Verifica periódicamente el estado del SRI (cada 2 minutos) y detecta
 * transiciones UP→DOWN ({@code system.incident}) y DOWN→UP
 * ({@code system.resolved}). También verifica diariamente certificados
 * expirados ({@code certificate.expired}) y permite broadcasting manual de
 * ventanas de mantenimiento ({@code system.maintenance}).</p>
 */
@ApplicationScoped
public class SystemStatusMonitor {

    private static final Logger log = Logger.getLogger(SystemStatusMonitor.class);

    final AtomicReference<Boolean> lastSriReceptionUp = new AtomicReference<>(null);
    final AtomicReference<Boolean> lastSriAuthorizationUp = new AtomicReference<>(null);

    @Inject
    @Liveness
    SriReceptionHealthCheck sriReceptionHealth;

    @Inject
    @Liveness
    SriAuthorizationHealthCheck sriAuthorizationHealth;

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "key49.webhook.enabled", defaultValue = "true")
    boolean webhookEnabled;

    @ConfigProperty(name = "key49.webhook.connect-timeout-ms", defaultValue = "5000")
    int connectTimeoutMs;

    @ConfigProperty(name = "key49.webhook.read-timeout-ms", defaultValue = "10000")
    int readTimeoutMs;

    /**
     * Verifica el estado de los servicios SOAP del SRI cada 2 minutos. Si
     * detecta un cambio de estado (UP→DOWN o DOWN→UP), envía webhook a todos
     * los tenants activos.
     */
    @Scheduled(every = "2m", identity = "system-status-monitor")
    void checkSriStatus() {
        if (!webhookEnabled) {
            return;
        }
        checkComponent("sri_reception", sriReceptionHealth, lastSriReceptionUp);
        checkComponent("sri_authorization", sriAuthorizationHealth, lastSriAuthorizationUp);
    }

    /**
     * Verifica diariamente certificados expirados y envía
     * {@code certificate.expired} a cada tenant afectado.
     */
    @Scheduled(cron = "0 0 7 * * ?", timeZone = "America/Guayaquil", identity = "certificate-expired-check")
    void checkExpiredCertificates() {
        if (!webhookEnabled) {
            return;
        }

        try (var conn = dataSource.getConnection(); var stmt = conn.prepareStatement(
                "SELECT tenant_id, legal_name, webhook_url, webhook_secret, certificate_expiration "
                + "FROM public.tenants "
                + "WHERE status = 'active' AND certificate_expiration IS NOT NULL "
                + "AND certificate_expiration < ? AND webhook_url IS NOT NULL AND webhook_url != '' "
                + "ORDER BY certificate_expiration ASC")) {

            stmt.setTimestamp(1, Timestamp.from(Instant.now()));
            try (var rs = stmt.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    var tenantId = rs.getObject("tenant_id", java.util.UUID.class).toString();
                    var legalName = rs.getString("legal_name");
                    var webhookUrl = rs.getString("webhook_url");
                    var webhookSecret = rs.getString("webhook_secret");
                    var expiration = rs.getTimestamp("certificate_expiration").toInstant();

                    var payload = buildCertExpiredPayload(tenantId, legalName, expiration);
                    sendWebhook(webhookUrl, webhookSecret, "certificate.expired", payload);
                }
                if (count > 0) {
                    log.infof("Certificate expired check: notified %d tenant(s)", count);
                }
            }
        } catch (Exception e) {
            log.errorf(e, "Error checking expired certificates");
        }
    }

    /**
     * Envía {@code system.maintenance} a todos los tenants activos con webhook
     * configurado.
     *
     * @param message descripción de la ventana de mantenimiento
     * @param scheduledAt fecha/hora programada del mantenimiento
     */
    public void broadcastMaintenance(String message, Instant scheduledAt) {
        if (!webhookEnabled) {
            return;
        }

        var payload = """
                {"event":"system.maintenance","message":"%s","scheduled_at":"%s","timestamp":"%s"}"""
                .formatted(escapeJson(message), scheduledAt, Instant.now());

        broadcastToAllTenants("system.maintenance", payload);
        log.infof("System maintenance broadcast: %s scheduled at %s", message, scheduledAt);
    }

    void checkComponent(String name, HealthCheck healthCheck, AtomicReference<Boolean> lastState) {
        var response = healthCheck.call();
        var currentlyUp = response.getStatus() == HealthCheckResponse.Status.UP;
        var previousUp = lastState.getAndSet(currentlyUp);

        if (previousUp == null || previousUp == currentlyUp) {
            return;
        }

        if (!currentlyUp) {
            var detail = extractError(response);
            var payload = """
                    {"event":"system.incident","component":"%s","status":"down","details":"%s","timestamp":"%s"}"""
                    .formatted(name, escapeJson(detail), Instant.now());
            broadcastToAllTenants("system.incident", payload);
            log.warnf("System incident: %s is DOWN", name);
        } else {
            var payload = """
                    {"event":"system.resolved","component":"%s","status":"operational","timestamp":"%s"}"""
                    .formatted(name, Instant.now());
            broadcastToAllTenants("system.resolved", payload);
            log.infof("System resolved: %s is back UP", name);
        }
    }

    void broadcastToAllTenants(String event, String payload) {
        try (var conn = dataSource.getConnection(); var stmt = conn.prepareStatement(
                "SELECT webhook_url, webhook_secret FROM public.tenants "
                + "WHERE status = 'active' AND webhook_url IS NOT NULL AND webhook_url != ''")) {

            try (var rs = stmt.executeQuery()) {
                int sent = 0;
                while (rs.next()) {
                    var url = rs.getString("webhook_url");
                    var secret = rs.getString("webhook_secret");
                    sendWebhook(url, secret, event, payload);
                    sent++;
                }
                log.debugf("Broadcast %s webhook to %d tenant(s)", event, sent);
            }
        } catch (Exception e) {
            log.errorf(e, "Error broadcasting %s webhook", event);
        }
    }

    void sendWebhook(String url, String secret, String event, String payload) {
        try {
            WebhookUrlValidator.validate(url);
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("Content-Type", "application/json")
                    .header("X-Key49-Event", event)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));

            if (secret != null && !secret.isBlank()) {
                requestBuilder.header("X-Key49-Signature", "sha256=" + computeHmac(payload, secret));
            }

            client.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenAccept(r -> log.debugf("System webhook sent: event=%s status=%d", event, r.statusCode()))
                    .exceptionally(e -> {
                        log.errorf(e, "Failed to send system webhook: event=%s url=%s", event, url);
                        return null;
                    });
        } catch (Exception e) {
            log.warnf("Failed to dispatch system webhook: event=%s url=%s error=%s", event, url, e.getMessage());
        }
    }

    static String computeHmac(String payload, String secret) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.errorf(e, "Failed to compute HMAC signature");
            return "";
        }
    }

    static String extractError(HealthCheckResponse response) {
        return response.getData()
                .map(d -> d.getOrDefault("error", "unavailable").toString())
                .orElse("unavailable");
    }

    static String buildCertExpiredPayload(String tenantId, String legalName, Instant expiration) {
        return """
                {"event":"certificate.expired","tenant_id":"%s","legal_name":"%s","expired_at":"%s","timestamp":"%s"}"""
                .formatted(tenantId, escapeJson(legalName), expiration, Instant.now());
    }

    static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    void resetState() {
        lastSriReceptionUp.set(null);
        lastSriAuthorizationUp.set(null);
    }
}
