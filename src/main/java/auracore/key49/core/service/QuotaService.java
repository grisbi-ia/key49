package auracore.key49.core.service;

import java.time.Instant;
import java.util.UUID;

import auracore.key49.api.exception.BusinessException;
import auracore.key49.notify.plan.PlanAlertService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * Gestiona la cuota de documentos por tenant. Provee incremento atómico al
 * crear documentos y decremento al rechazarlos o fallarlos.
 */
@ApplicationScoped
public class QuotaService {

    @Inject
    PlanAlertService planAlertService;

    /**
     * Reserva una unidad de cuota para el tenant. Verifica expiración del plan
     * y disponibilidad de cuota con un UPDATE atómico que previene race
     * conditions.
     *
     * @param em EntityManager activo dentro de una transacción
     * @param tenantId UUID del tenant en public.tenants
     * @throws BusinessException 402 PLAN_EXPIRED si el plan está vencido
     * @throws BusinessException 402 QUOTA_EXHAUSTED si no queda cuota
     */
    public void reserveQuota(EntityManager em, UUID tenantId) {
        var row = (Object[]) em.createNativeQuery(
                "SELECT plan_expires_at, documents_used, document_quota "
                + "FROM public.tenants WHERE tenant_id = ?1")
                .setParameter(1, tenantId)
                .getSingleResult();

        var expiresAt = row[0] != null ? (row[0] instanceof Instant i ? i
                : ((java.sql.Timestamp) row[0]).toInstant()) : null;
        var used = ((Number) row[1]).intValue();
        var quota = ((Number) row[2]).intValue();

        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
            Log.warnf("Quota check: plan expired for tenant=%s, expiresAt=%s", tenantId, expiresAt);
            throw new BusinessException("PLAN_EXPIRED", "Plan expirado", 402);
        }

        if (used >= quota) {
            Log.warnf("Quota check: quota exhausted for tenant=%s (%d/%d)", tenantId, used, quota);
            throw new BusinessException("QUOTA_EXHAUSTED",
                    "Cuota de documentos agotada. Renueve su plan.", 402);
        }

        int updated = em.createNativeQuery(
                "UPDATE public.tenants SET documents_used = documents_used + 1, "
                + "updated_at = now() "
                + "WHERE tenant_id = ?1 AND documents_used < document_quota")
                .setParameter(1, tenantId)
                .executeUpdate();

        if (updated == 0) {
            Log.warnf("Quota check: atomic increment failed (race) for tenant=%s", tenantId);
            throw new BusinessException("QUOTA_EXHAUSTED",
                    "Cuota de documentos agotada. Renueve su plan.", 402);
        }

        Log.debugf("Quota reserved: tenant=%s, used=%d/%d", tenantId, used + 1, quota);

        try {
            planAlertService.checkQuotaThresholds(tenantId, used, used + 1, quota);
        } catch (Exception e) {
            Log.errorf(e, "Failed to fire quota alerts for tenant=%s", tenantId);
        }
    }

    /**
     * Libera una unidad de cuota cuando un documento es rechazado o falla. Usa
     * GREATEST para evitar valores negativos.
     *
     * @param em EntityManager activo dentro de una transacción
     * @param schemaName nombre del esquema del tenant (e.g. 'tenant_abc123')
     */
    public void releaseQuota(EntityManager em, String schemaName) {
        int updated = em.createNativeQuery(
                "UPDATE public.tenants "
                + "SET documents_used = GREATEST(documents_used - 1, 0), "
                + "updated_at = now() "
                + "WHERE schema_name = ?1 AND documents_used > 0")
                .setParameter(1, schemaName)
                .executeUpdate();

        if (updated > 0) {
            Log.debugf("Quota released: schema=%s", schemaName);
        }
    }
}
