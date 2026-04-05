package auracore.key49.queue.consumer;

import auracore.key49.queue.event.DocumentEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Consumidor que genera RIDE (PDF), envía email y dispara webhooks.
 * Transición: AUTHORIZED → NOTIFIED.
 */
@ApplicationScoped
public class NotifyConsumer {

    @Inject
    Logger log;

    @Incoming("doc-notify-in")
    public Uni<Void> process(DocumentEvent event) {
        log.infof("NotifyConsumer: processing documentId=%s, tenant=%s", event.documentId(), event.tenantSchemaName());
        // TODO: T-013 — Implementar generación RIDE + email + webhook
        return Uni.createFrom().voidItem();
    }
}
