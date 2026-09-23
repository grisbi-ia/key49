package auracore.key49.queue.reconcile;

import java.time.Instant;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.model.enums.DocumentStatus;
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

    @ConfigProperty(name = "key49.reconcile.stale-minutes", defaultValue = "10")
    int staleMinutes;

    @ConfigProperty(name = "key49.recover.failed.cooldown-minutes", defaultValue = "15")
    int recoverFailedCooldownMinutes;

    @ConfigProperty(name = "key49.recover.failed.max-age-hours", defaultValue = "24")
    int recoverFailedMaxAgeHours;

    @ConfigProperty(name = "key49.recover.no-reg.cooldown-minutes", defaultValue = "15")
    int recoverNoRegCooldownMinutes;

    @ConfigProperty(name = "key49.recover.no-reg.max-age-hours", defaultValue = "24")
    int recoverNoRegMaxAgeHours;

    @ConfigProperty(name = "key49.recover.no-reg.max-attempts", defaultValue = "3")
    int recoverNoRegMaxAttempts;

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
            recoverStaleTransient(schemaName);
            recoverFailedInfra(schemaName);
            recoverNoReg(schemaName);
        } catch (Exception ex) {
            log.errorf(ex, "ReconciliationPoller: error reconciling tenant=%s", schemaName);
        }
    }

    /**
     * Recupera documentos atascados en estados transitorios (CREATED/SIGNED/SENT)
     * que no avanzaron desde {@code staleMinutes} (p. ej. reinicio del proceso
     * entre etapas): reencola la etapa correspondiente.
     */
    private void recoverStaleTransient(String schemaName) {
        var recovered = connectionManager.withTenantTransaction(schemaName, em -> {
            var docs = documentRepository.findStaleTransient(staleMinutes);
            int n = 0;
            for (var doc : docs) {
                String eventType;
                switch (doc.status) {
                    case CREATED -> eventType = "doc.sign";
                    case SIGNED -> eventType = "doc.send";
                    case SENT -> {
                        // Ya enviado al SRI: reconciliar autorización
                        doc.transitionTo(DocumentStatus.RECEIVED);
                        eventType = "doc.authorize";
                    }
                    default -> eventType = null;
                }
                if (eventType != null) {
                    doc.updatedAt = Instant.now();
                    em.persist(OutboxEvent.create(doc.id, eventType, "{}"));
                    n++;
                    log.infof("ReconciliationPoller: recovering stale %s document %s as %s (tenant=%s)",
                            doc.status, doc.id, eventType, schemaName);
                }
            }
            return n;
        });
        if (recovered != null && recovered > 0) {
            log.infof("ReconciliationPoller: %d stale document(s) recovered (tenant=%s)",
                    recovered, schemaName);
        }
    }

    /**
     * Recupera documentos {@code FAILED} por error de infraestructura (sin código de
     * negocio) tras un cooldown, dentro de una ventana maxima de antiguedad. Los
     * {@code FAILED} por error de negocio no se tocan.
     */
    private void recoverFailedInfra(String schemaName) {
        var recovered = connectionManager.withTenantTransaction(schemaName, em -> {
            var docs = documentRepository.findRecoverableFailed(
                    recoverFailedCooldownMinutes, recoverFailedMaxAgeHours);
            int n = 0;
            for (var doc : docs) {
                doc.retryCount = 0;
                doc.nextRetryAt = null;
                doc.lastErrorMessage = null;
                doc.updatedAt = Instant.now();
                String eventType;
                if (doc.sriSubmissionDate != null) {
                    // Ya fue enviado: reconciliar autorizacion
                    doc.transitionTo(DocumentStatus.RECEIVED);
                    eventType = "doc.authorize";
                } else {
                    // Nunca enviado: re-firmar y reenviar
                    doc.transitionTo(DocumentStatus.CREATED);
                    eventType = "doc.sign";
                }
                em.persist(OutboxEvent.create(doc.id, eventType, "{}"));
                n++;
                log.infof("ReconciliationPoller: recovering FAILED document %s as %s (tenant=%s)",
                        doc.id, eventType, schemaName);
            }
            return n;
        });
        if (recovered != null && recovered > 0) {
            log.infof("ReconciliationPoller: %d FAILED document(s) recovered (tenant=%s)",
                    recovered, schemaName);
        }
    }

    /**
     * Recupera documentos {@code REJECTED} con código {@code NO_REG}: Key49 no
     * encontró la clave de acceso en el SRI. Se re-emiten (re-firma y reenvío) de
     * forma acotada — cooldown, ventana máxima y número de intentos — para que un
     * comprobante no quede atascado sin intervención humana. Reenviar es seguro:
     * si el SRI ya lo tuviera respondería 43 (ya registrado) y se reconcilia.
     */
    private void recoverNoReg(String schemaName) {
        var recovered = connectionManager.withTenantTransaction(schemaName, em -> {
            var docs = documentRepository.findRecoverableNoReg(
                    recoverNoRegCooldownMinutes, recoverNoRegMaxAgeHours, recoverNoRegMaxAttempts);
            int n = 0;
            for (var doc : docs) {
                doc.retryCount++;
                doc.nextRetryAt = null;
                doc.lastErrorCode = null;
                doc.lastErrorMessage = null;
                doc.updatedAt = Instant.now();
                // Re-emitir: REJECTED → CREATED → re-firma (doc.sign) → reenvío.
                doc.transitionTo(DocumentStatus.CREATED);
                em.persist(OutboxEvent.create(doc.id, "doc.sign", "{}"));
                n++;
                log.infof("ReconciliationPoller: re-emitting NO_REG document %s as doc.sign (attempt %d/%d, tenant=%s)",
                        doc.id, doc.retryCount, recoverNoRegMaxAttempts, schemaName);
            }
            return n;
        });
        if (recovered != null && recovered > 0) {
            log.infof("ReconciliationPoller: %d NO_REG document(s) re-emitted (tenant=%s)",
                    recovered, schemaName);
        }
    }
}
