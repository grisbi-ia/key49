package auracore.key49.core.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.jboss.logging.Logger;

import auracore.key49.core.model.PlanRenewal;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.model.enums.PlanType;
import auracore.key49.core.repository.PlanRenewalRepository;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.notify.plan.PlanAlertService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Job programado diario que gestiona la expiración y auto-renovación de planes.
 *
 * <p>
 * Ejecuta a las 00:05 ECT cada día y procesa tenants con
 * {@code plan_expires_at <= now()}:</p>
 * <ul>
 * <li>Planes no-Enterprise (DEMO, STARTER, BUSINESS): marca
 * {@code status = 'expired'}, dispara webhook {@code plan.expired} y email de
 * notificación.</li>
 * <li>Planes Enterprise con auto-renovación: resetea
 * {@code documents_used = 0}, extiende {@code plan_expires_at} 30 días, crea
 * registro en {@code plan_renewals} y dispara webhook
 * {@code plan.renewed}.</li>
 * </ul>
 */
@ApplicationScoped
public class PlanExpirationService {

    private static final Logger log = Logger.getLogger(PlanExpirationService.class);
    static final int RENEWAL_PERIOD_DAYS = 30;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    PlanRenewalRepository renewalRepository;

    @Inject
    TenantCacheService tenantCacheService;

    @Inject
    PlanAlertService planAlertService;

    public record ExpirationResult(int expired, int renewed) {

    }

    /**
     * Job diario (00:05 ECT) que verifica planes expirados y los procesa.
     */
    @Scheduled(cron = "0 5 0 * * ?", timeZone = "America/Guayaquil", identity = "plan-expiration-job")
    void processExpiredPlans() {
        log.info("Starting plan expiration check...");
        var result = checkAndProcess(Instant.now());
        log.infof("Plan expiration check complete: %d expired, %d auto-renewed", result.expired(), result.renewed());
    }

    /**
     * Lógica principal del job, extraída para testabilidad.
     *
     * @param now instante de referencia para evaluar expiración
     * @return resultado con contadores de planes expirados y renovados
     */
    @Transactional
    public ExpirationResult checkAndProcess(Instant now) {
        List<Tenant> expiredTenants = tenantRepository.findExpiredActive(now);

        int expiredCount = 0;
        int renewedCount = 0;

        for (var tenant : expiredTenants) {
            try {
                if (isEnterprise(tenant)) {
                    autoRenew(tenant, now);
                    renewedCount++;
                } else {
                    expire(tenant, now);
                    expiredCount++;
                }
            } catch (Exception e) {
                log.errorf(e, "Error processing expired plan | tenant=%s plan=%s",
                        tenant.id, tenant.planType);
            }
        }

        return new ExpirationResult(expiredCount, renewedCount);
    }

    boolean isEnterprise(Tenant tenant) {
        return PlanType.ENTERPRISE.code().equals(tenant.planType);
    }

    void expire(Tenant tenant, Instant now) {
        tenant.status = "expired";
        tenant.updatedAt = now;

        tenantCacheService.invalidate(tenant.id, tenant.schemaName);

        log.infof("Plan expired | tenant=%s plan=%s ruc=%s",
                tenant.id, tenant.planType, tenant.ruc);

        fireExpiredAlert(tenant);
    }

    void autoRenew(Tenant tenant, Instant now) {
        var previousUsed = tenant.documentsUsed;
        var previousExpiresAt = tenant.planExpiresAt;

        tenant.documentsUsed = 0;
        tenant.planStartsAt = now;
        tenant.planExpiresAt = now.plus(RENEWAL_PERIOD_DAYS, ChronoUnit.DAYS);
        tenant.updatedAt = now;

        // Crear registro de historial en plan_renewals
        var renewal = new PlanRenewal();
        renewal.tenantId = tenant.id;
        renewal.planType = tenant.planType;
        renewal.documentQuota = tenant.documentQuota;
        renewal.amount = BigDecimal.ZERO;
        renewal.status = "approved";
        renewal.approvedBy = "system-auto-renewal";
        renewal.approvedAt = now;
        renewal.notes = "Auto-renewal | previous_used=%d previous_expires_at=%s"
                .formatted(previousUsed, previousExpiresAt);
        renewal.createdAt = now;
        renewalRepository.persist(renewal);

        tenantCacheService.invalidate(tenant.id, tenant.schemaName);

        log.infof("Plan auto-renewed | tenant=%s plan=%s quota=%d previousUsed=%d",
                tenant.id, tenant.planType, tenant.documentQuota, previousUsed);

        fireRenewedAlert(tenant, renewal);
    }

    private void fireExpiredAlert(Tenant tenant) {
        if (tenant.webhookUrl == null || tenant.webhookUrl.isBlank()) {
            return;
        }
        try {
            var payload = """
                    {"event":"plan.expired","tenant_id":"%s",\
                    "legal_name":"%s","plan_type":"%s",\
                    "plan_expires_at":"%s","timestamp":"%s"}\
                    """.formatted(
                    tenant.id, escapeJson(tenant.legalName),
                    tenant.planType, tenant.planExpiresAt,
                    Instant.now().toString());
            planAlertService.fireAlert("plan.expired",
                    tenant.webhookUrl, tenant.webhookSecret,
                    payload, tenant.replyEmail,
                    "Key49 — Plan expirado",
                    expiredEmailBody(tenant));
        } catch (Exception e) {
            log.errorf(e, "Failed to fire plan.expired alert | tenant=%s", tenant.id);
        }
    }

    private void fireRenewedAlert(Tenant tenant, PlanRenewal renewal) {
        if (tenant.webhookUrl == null || tenant.webhookUrl.isBlank()) {
            return;
        }
        try {
            var payload = """
                    {"event":"plan.renewed","tenant_id":"%s",\
                    "legal_name":"%s","plan_type":"%s",\
                    "document_quota":%d,"plan_starts_at":"%s",\
                    "plan_expires_at":"%s","timestamp":"%s"}\
                    """.formatted(
                    tenant.id, escapeJson(tenant.legalName),
                    tenant.planType, tenant.documentQuota,
                    tenant.planStartsAt, tenant.planExpiresAt,
                    Instant.now().toString());
            planAlertService.fireAlert("plan.renewed",
                    tenant.webhookUrl, tenant.webhookSecret,
                    payload, tenant.replyEmail,
                    "Key49 — Plan renovado automáticamente",
                    renewedEmailBody(tenant));
        } catch (Exception e) {
            log.errorf(e, "Failed to fire plan.renewed alert | tenant=%s", tenant.id);
        }
    }

    private String expiredEmailBody(Tenant tenant) {
        return """
                Estimado/a contribuyente,

                Le informamos que su plan en Key49 ha expirado.

                Razón Social: %s
                RUC: %s
                Plan: %s

                Su cuenta ha sido suspendida y no podrá emitir nuevos comprobantes \
                electrónicos hasta que renueve su plan.

                Para renovar, ingrese al portal de Key49 y solicite una renovación \
                desde la sección "Mi Plan".

                Atentamente,
                Key49 - Facturación Electrónica
                """.formatted(tenant.legalName, tenant.ruc, tenant.planType);
    }

    private String renewedEmailBody(Tenant tenant) {
        return """
                Estimado/a contribuyente,

                Su plan Enterprise en Key49 ha sido renovado automáticamente.

                Razón Social: %s
                RUC: %s
                Plan: %s
                Documentos disponibles: %d
                Vigencia: 30 días a partir de hoy

                Su cuota de documentos ha sido reiniciada. Puede continuar \
                emitiendo comprobantes electrónicos sin interrupciones.

                Atentamente,
                Key49 - Facturación Electrónica
                """.formatted(tenant.legalName, tenant.ruc, tenant.planType, tenant.documentQuota);
    }

    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
