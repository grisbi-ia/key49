package auracore.key49.queue.consumer;

import java.time.Instant;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.InvalidStateTransitionException;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.queue.event.DocumentEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Consumidor de la Dead Letter Queue.
 * Registra errores definitivos y marca el documento como FAILED.
 */
@ApplicationScoped
public class DlqConsumer {

    @Inject
    Logger log;

    @Inject
    TenantConnectionManager connectionManager;

    @Incoming("doc-dlq-in")
    public Uni<Void> process(DocumentEvent event) {
        log.errorf("DLQ: processing failed document — documentId=%s, tenant=%s, retryCount=%d",
                event.documentId(), event.tenantSchemaName(), event.retryCount());

        return connectionManager.withTenantTransaction(event.tenantSchemaName(), session ->
                session.find(Document.class, event.documentId())
                        .chain(doc -> {
                            if (doc == null) {
                                log.warnf("DLQ: document not found: %s", event.documentId());
                                return Uni.createFrom().voidItem();
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

                            return Uni.createFrom().voidItem();
                        })
        ).onFailure().invoke(ex ->
                log.errorf(ex, "DLQ: error processing document %s", event.documentId()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }
}
