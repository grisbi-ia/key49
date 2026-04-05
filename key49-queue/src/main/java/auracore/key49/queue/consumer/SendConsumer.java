package auracore.key49.queue.consumer;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import auracore.key49.queue.event.DocumentEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Consumidor que envía documentos firmados al SRI vía SOAP Recepción.
 * Transición: SIGNED → SENT/RECEIVED (éxito), SIGNED → RETRY (infra), SIGNED → REJECTED (negocio).
 */
@ApplicationScoped
public class SendConsumer {

    @Inject
    Logger log;

    @Incoming("doc-send-in")
    public Uni<Void> process(DocumentEvent event) {
        log.infof("SendConsumer: processing documentId=%s, tenant=%s", event.documentId(), event.tenantSchemaName());
        // TODO: T-013 — Implementar envío SOAP al SRI
        return Uni.createFrom().voidItem();
    }
}
