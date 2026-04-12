package auracore.key49.queue.consumer;

import java.time.Instant;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import auracore.key49.admin.metrics.DocumentMetrics;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.InvalidStateTransitionException;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.service.QuotaService;
import auracore.key49.core.tenant.MdcContext;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.queue.event.DocumentEvent;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Consumidor de la Dead Letter Queue. Registra errores definitivos y marca el
 * documento como FAILED.
 */
@ApplicationScoped
public class DlqConsumer {

    @Inject
    Logger log;

    @Inject
    ConsumerErrorHandler errorHandler;

    @Inject
    TenantConnectionManager connectionManager;

    @Inject
    DocumentMetrics documentMetrics;

    @Inject
    QuotaService quotaService;

    @Inject
    InFlightTracker tracker;

    @Incoming("doc-dlq-in")
    @Blocking
    public void process(JsonObject json) {
        tracker.increment("DlqConsumer");
        try {
            var event = DocumentEvent.fromJson(json);
            MdcContext.setTenant(event.tenantSchemaName());
            MdcContext.setDocument(event.documentId());
            log.errorf("DLQ: processing failed document — documentId=%s, tenant=%s, retryCount=%d",
                    event.documentId(), event.tenantSchemaName(), event.retryCount());

            try {
                connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
                    var doc = em.find(Document.class, event.documentId());
                    if (doc == null) {
                        log.warnf("DLQ: document not found: %s", event.documentId());
                        return null;
                    }

                    if (!doc.status.isTerminal()) {
                        try {
                            doc.transitionTo(DocumentStatus.FAILED);
                            quotaService.releaseQuota(em, event.tenantSchemaName());
                        } catch (InvalidStateTransitionException e) {
                            log.warnf("DLQ: cannot transition to FAILED from %s for document %s",
                                    doc.status, doc.id);
                        }
                        doc.lastErrorMessage = "Exhausted all retries — moved to DLQ";
                        doc.updatedAt = Instant.now();
                        documentMetrics.recordFailed(event.tenantSchemaName());
                    }

                    // TODO: T-014 — Log to audit_log table
                    // TODO: T-017 — Dispatch error webhook to tenant
                    return null;
                });
            } catch (Exception ex) {
                errorHandler.persistError(event.documentId(), event.tenantSchemaName(),
                        "DlqConsumer", ex);
            }
        } finally {
            MdcContext.clear();
            tracker.decrement("DlqConsumer");
        }
    }
}
