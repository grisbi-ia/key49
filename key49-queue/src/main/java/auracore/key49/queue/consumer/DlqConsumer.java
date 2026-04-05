package auracore.key49.queue.consumer;

import auracore.key49.queue.event.DocumentEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Consumidor de la Dead Letter Queue.
 * Registra errores definitivos y notifica al tenant vía webhook de error.
 */
@ApplicationScoped
public class DlqConsumer {

    @Inject
    Logger log;

    @Incoming("doc-dlq-in")
    public Uni<Void> process(DocumentEvent event) {
        log.errorf("DLQ: document failed definitively — documentId=%s, tenant=%s, retryCount=%d",
                event.documentId(), event.tenantSchemaName(), event.retryCount());
        // TODO: T-014 — Registrar en audit_log y disparar webhook de error al tenant
        return Uni.createFrom().voidItem();
    }
}
