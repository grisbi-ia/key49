package auracore.key49.queue.retry;

import java.time.Instant;

import org.jboss.logging.Logger;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.repository.DocumentRepository;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.service.QuotaService;
import auracore.key49.core.tenant.TenantConnectionManager;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;

/**
 * Poller que busca documentos en estado RETRY cuyo {@code nextRetryAt} ha vencido
 * y los re-encola al pipeline creando un evento de outbox.
 *
 * <p>Ejecuta cada 5s (configurable). Determina el tipo de reintento según el estado
 * del documento: si ya fue enviado al SRI → doc.authorize, de lo contrario → doc.send.
 */
@ApplicationScoped
public class RetryPoller {

    @Inject
    Logger log;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    TenantConnectionManager connectionManager;

    @Inject
    DocumentRepository documentRepository;

    @Inject
    QuotaService quotaService;

    @Scheduled(every = "${key49.retry.poll-interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void pollRetries() {
        try {
            var tenants = tenantRepository.findAllActive();
            for (var tenant : tenants) {
                pollTenant(tenant.schemaName);
            }
        } catch (Exception ex) {
            log.errorf(ex, "RetryPoller: error during poll cycle");
        }
    }

    private void pollTenant(String schemaName) {
        try {
            connectionManager.withTenantTransaction(schemaName, em -> {
                var docs = documentRepository.findRetryReady();
                if (docs.isEmpty()) {
                    return null;
                }
                log.infof("RetryPoller: %d retry-ready documents for tenant=%s",
                        docs.size(), schemaName);
                for (var doc : docs) {
                    requeueDocument(doc, em, schemaName);
                }
                return null;
            });
        } catch (Exception ex) {
            log.errorf(ex, "RetryPoller: error polling tenant=%s", schemaName);
        }
    }

    private void requeueDocument(Document doc, EntityManager em, String schemaName) {
        if (RetryDelayCalculator.isExhausted(doc.retryCount, doc.maxRetries)) {
            log.warnf("RetryPoller: retries exhausted for document %s (retryCount=%d, maxRetries=%d)",
                    doc.id, doc.retryCount, doc.maxRetries);
            doc.transitionTo(DocumentStatus.FAILED);
            quotaService.releaseQuota(em, schemaName);
            doc.lastErrorMessage = "Max retries exhausted (%d/%d)".formatted(doc.retryCount, doc.maxRetries);
            doc.updatedAt = Instant.now();
            return;
        }

        var eventType = resolveRetryEventType(doc);
        var outbox = OutboxEvent.create(doc.id, eventType, "{}");
        log.infof("RetryPoller: requeuing document %s as %s (retry %d/%d)",
                doc.id, eventType, doc.retryCount, doc.maxRetries);
        em.persist(outbox);
    }

    /**
     * Determina a qué cola reenviar el documento según su contexto.
     * Si ya fue enviado al SRI (sriSubmissionDate != null) → doc.authorize.
     * Si no fue enviado → doc.send.
     */
    static String resolveRetryEventType(Document doc) {
        if (doc.sriSubmissionDate != null) {
            return "doc.authorize";
        }
        return "doc.send";
    }
}
