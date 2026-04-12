package auracore.key49.core.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.jboss.logging.Logger;

import auracore.key49.core.model.PlanRenewal;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.PlanRenewalRepository;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.notify.plan.PlanAlertService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Servicio de administración de renovaciones de plan. Permite listar, aprobar y
 * rechazar solicitudes de renovación enviadas por los tenants.
 *
 * <p>
 * Al aprobar una renovación se actualiza el plan del tenant, se reinicia la
 * cuota de documentos, se invalida la caché Redis del tenant, y se notifica al
 * tenant vía webhook ({@code plan.activated}) y email.</p>
 */
@ApplicationScoped
public class RenewalAdminService {

    private static final Logger log = Logger.getLogger(RenewalAdminService.class);

    @Inject
    PlanRenewalRepository renewalRepository;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    TenantCacheService tenantCacheService;

    @Inject
    PlanAlertService planAlertService;

    public record ApproveResult(boolean success, String error, PlanRenewal renewal) {

    }

    public record RejectResult(boolean success, String error, PlanRenewal renewal) {

    }

    public record RenewalPage(List<PlanRenewal> items, long total) {

    }

    public record RenewalDetail(PlanRenewal renewal, Tenant tenant) {

    }

    /**
     * Lista renovaciones con filtro opcional por estado y paginación.
     */
    public RenewalPage list(String status, int page, int perPage) {
        if (status != null && !status.isBlank()) {
            var items = renewalRepository.findByStatus(status, page, perPage);
            var total = renewalRepository.countByStatus(status);
            return new RenewalPage(items, total);
        }
        var items = renewalRepository.findAllPaged(page, perPage);
        var total = renewalRepository.count();
        return new RenewalPage(items, total);
    }

    /**
     * Obtiene detalle de una renovación con datos del tenant asociado.
     */
    public RenewalDetail getDetail(UUID renewalId) {
        var renewal = renewalRepository.findById(renewalId);
        if (renewal == null) {
            return null;
        }
        var tenant = tenantRepository.findById(renewal.tenantId);
        return new RenewalDetail(renewal, tenant);
    }

    /**
     * Aprueba una renovación: actualiza plan del tenant, reinicia cuota,
     * invalida caché Redis, notifica al tenant.
     *
     * @param renewalId ID de la renovación
     * @param approvedBy identificador del admin que aprueba
     * @return resultado de la operación
     */
    @Transactional
    public ApproveResult approve(UUID renewalId, String approvedBy) {
        var renewal = renewalRepository.findById(renewalId);
        if (renewal == null) {
            return new ApproveResult(false, "Renovación no encontrada", null);
        }
        if (!"pending".equals(renewal.status)) {
            return new ApproveResult(false,
                    "Solo se pueden aprobar renovaciones en estado pendiente (actual: %s)"
                            .formatted(renewal.status),
                    null);
        }

        var tenant = tenantRepository.findById(renewal.tenantId);
        if (tenant == null) {
            return new ApproveResult(false, "Tenant no encontrado", null);
        }

        // Actualizar renovación
        renewal.status = "approved";
        renewal.approvedBy = approvedBy;
        renewal.approvedAt = Instant.now();

        // Actualizar tenant
        tenant.planType = renewal.planType;
        tenant.documentQuota = renewal.documentQuota;
        tenant.documentsUsed = 0;
        tenant.planStartsAt = Instant.now();
        tenant.planExpiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
        tenant.updatedAt = Instant.now();

        // Invalidar caché Redis del tenant
        tenantCacheService.invalidate(tenant.id, tenant.schemaName);

        log.infof("Plan renewal approved | renewal=%s tenant=%s plan=%s quota=%d approvedBy=%s",
                renewalId, tenant.id, renewal.planType, renewal.documentQuota, approvedBy);

        // Notificar al tenant
        fireActivatedAlert(tenant, renewal);

        return new ApproveResult(true, null, renewal);
    }

    /**
     * Rechaza una renovación con motivo.
     *
     * @param renewalId ID de la renovación
     * @param reason motivo del rechazo
     * @param rejectedBy identificador del admin que rechaza
     * @return resultado de la operación
     */
    @Transactional
    public RejectResult reject(UUID renewalId, String reason, String rejectedBy) {
        var renewal = renewalRepository.findById(renewalId);
        if (renewal == null) {
            return new RejectResult(false, "Renovación no encontrada", null);
        }
        if (!"pending".equals(renewal.status)) {
            return new RejectResult(false,
                    "Solo se pueden rechazar renovaciones en estado pendiente (actual: %s)"
                            .formatted(renewal.status),
                    null);
        }

        var tenant = tenantRepository.findById(renewal.tenantId);

        renewal.status = "rejected";
        renewal.approvedBy = rejectedBy;
        renewal.approvedAt = Instant.now();
        renewal.notes = (renewal.notes != null ? renewal.notes + "\n\nMotivo de rechazo: " : "Motivo de rechazo: ")
                + reason;

        log.infof("Plan renewal rejected | renewal=%s tenant=%s reason=%s rejectedBy=%s",
                renewalId, renewal.tenantId, reason, rejectedBy);

        // Notificar al tenant
        if (tenant != null) {
            fireRejectedAlert(tenant, renewal, reason);
        }

        return new RejectResult(true, null, renewal);
    }

    private void fireActivatedAlert(Tenant tenant, PlanRenewal renewal) {
        if (tenant.webhookUrl == null || tenant.webhookUrl.isBlank()) {
            return;
        }
        try {
            var payload = """
                    {"event":"plan.activated","renewal_id":"%s",\
                    "tenant_id":"%s","legal_name":"%s",\
                    "plan_type":"%s","document_quota":%d,\
                    "plan_starts_at":"%s","plan_expires_at":"%s",\
                    "timestamp":"%s"}\
                    """.formatted(
                    renewal.id, tenant.id, escapeJson(tenant.legalName),
                    tenant.planType, tenant.documentQuota,
                    tenant.planStartsAt, tenant.planExpiresAt,
                    Instant.now().toString());
            planAlertService.fireAlert("plan.activated",
                    tenant.webhookUrl, tenant.webhookSecret,
                    payload, tenant.replyEmail,
                    "Key49 — Plan activado: %s".formatted(tenant.planType),
                    activatedEmailBody(tenant, renewal));
        } catch (Exception e) {
            log.errorf(e, "Failed to fire plan.activated webhook | tenant=%s", tenant.id);
        }
    }

    private void fireRejectedAlert(Tenant tenant, PlanRenewal renewal, String reason) {
        if (tenant.webhookUrl == null || tenant.webhookUrl.isBlank()) {
            return;
        }
        try {
            var payload = """
                    {"event":"plan.rejected","renewal_id":"%s",\
                    "tenant_id":"%s","legal_name":"%s",\
                    "requested_plan":"%s","reason":"%s",\
                    "timestamp":"%s"}\
                    """.formatted(
                    renewal.id, tenant.id, escapeJson(tenant.legalName),
                    renewal.planType, escapeJson(reason),
                    Instant.now().toString());
            planAlertService.fireAlert("plan.rejected",
                    tenant.webhookUrl, tenant.webhookSecret,
                    payload, tenant.replyEmail,
                    "Key49 — Solicitud de renovación rechazada",
                    rejectedEmailBody(tenant, reason));
        } catch (Exception e) {
            log.errorf(e, "Failed to fire plan.rejected webhook | tenant=%s", tenant.id);
        }
    }

    private String activatedEmailBody(Tenant tenant, PlanRenewal renewal) {
        return """
                Estimado/a contribuyente,

                Su plan en Key49 ha sido actualizado exitosamente.

                Razón Social: %s
                Nuevo plan: %s
                Documentos disponibles: %d
                Vigencia: 30 días a partir de hoy

                Su cuota de documentos ha sido reiniciada. Puede comenzar a emitir comprobantes \
                electrónicos con su nuevo plan.

                Atentamente,
                Key49 - Facturación Electrónica
                """.formatted(tenant.legalName, renewal.planType, renewal.documentQuota);
    }

    private String rejectedEmailBody(Tenant tenant, String reason) {
        return """
                Estimado/a contribuyente,

                Lamentamos informarle que su solicitud de renovación de plan ha sido rechazada.

                Razón Social: %s
                Motivo: %s

                Si tiene preguntas, puede contactar al soporte de Key49.

                Atentamente,
                Key49 - Facturación Electrónica
                """.formatted(tenant.legalName, reason);
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
