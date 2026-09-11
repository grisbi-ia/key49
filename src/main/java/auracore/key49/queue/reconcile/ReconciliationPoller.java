package auracore.key49.queue.reconcile;

import java.time.Instant;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.repository.DocumentRepository;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.tenant.TenantConnectionManager;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Reconcilia la autorización de documentos que el SRI todavía no ha resuelto
 * (código 70 "en procesamiento", respuesta sin {@code <autorizacion>}, etc.).
 *
 * <p>El {@code AuthorizeConsumer} deja estos documentos en estado
 * {@code RECEIVED} con un {@code nextRetryAt} futuro, sin consumir el
 * presupuesto de reintentos de error. Este poller los vuelve a encolar como
 * {@code doc.authorize} pasada la ventana, sin reenviarlos al SRI.</p>
 *
 * <p>Aislamiento multi-tenant: itera los tenants activos y ejecuta cada
 * operación con {@code SET search_path} al esquema del tenant. El
 * {@code schemaName} proviene de la tabla {@code public.tenants} (dato
 * confiable) y es validado por {@link TenantConnectionManager}.</p>
 */
@ApplicationScoped
public class ReconciliationPoller {

    @Inject
    Logger log;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    TenantConnectionManager connectionManager;

    @Inject
    DocumentRepository documentRepository;

    @ConfigProperty(name = "key49.reconcile.max-age-days", defaultValue = "3")
    int maxAgeDays;

    @ConfigProperty(name = "key49.reconcile.batch-size", defaultValue = "100")
    int batchSize;

    @Scheduled(every = "${key49.reconcile.poll-interval:2m}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void pollReconciliations() {
        try {
            var tenants = tenantRepository.findAllActive();
            for (var tenant : tenants) {
                pollTenant(tenant.schemaName);
            }
        } catch (Exception ex) {
            log.errorf(ex, "ReconciliationPoller: error during poll cycle");
        }
    }

    private void pollTenant(String schemaName) {
        try {
            var count = connectionManager.withTenantTransaction(schemaName, em -> {
                var docs = documentRepository.findReconcileReady(maxAgeDays);
                if (docs.isEmpty()) {
                    return 0;
                }
                int limit = Math.min(docs.size(), batchSize);
                for (int i = 0; i < limit; i++) {
                    var doc = docs.get(i);
                    doc.nextRetryAt = null;
                    doc.updatedAt = Instant.now();
                    em.persist(OutboxEvent.create(doc.id, "doc.authorize", "{}"));
                }
                return limit;
            });
            if (count != null && count > 0) {
                log.infof("ReconciliationPoller: %d document(s) queued for reconciliation (tenant=%s)",
                        count, schemaName);
            }
        } catch (Exception ex) {
            log.errorf(ex, "ReconciliationPoller: error reconciling tenant=%s", schemaName);
        }
    }
}
