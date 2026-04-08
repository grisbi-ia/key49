package auracore.key49.queue.consumer;

import java.time.Instant;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.InvalidStateTransitionException;
import auracore.key49.core.model.enums.DocumentStatus;
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

    @Incoming("doc-dlq-in")
    @Blocking
    public void process(JsonObject json) {
        var event = DocumentEvent.fromJson(json);
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
                    } catch (InvalidStateTransitionException e) {
                        log.warnf("DLQ: cannot transition to FAILED from %s for document %s",
                                doc.status, doc.id);
                    }
                    doc.lastErrorMessage = "Exhausted all retries — moved to DLQ";
                    doc.updatedAt = Instant.now();
                }

                // TODO: T-014 — Log to audit_log table
                // TODO: T-017 — Dispatch error webhook to tenant
                return null;
            });
        } catch (Exception ex) {
            errorHandler.persistError(event.documentId(), event.tenantSchemaName(),
                    "DlqConsumer", ex);
        }
    }
}
