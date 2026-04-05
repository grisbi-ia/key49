package auracore.key49.queue.consumer;

import auracore.key49.queue.event.DocumentEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Consumidor que consulta la autorización del SRI vía SOAP Autorización.
 * Transición: RECEIVED → AUTHORIZED (éxito), RECEIVED → REJECTED (negocio), RECEIVED → RETRY (infra).
 */
@ApplicationScoped
public class AuthorizeConsumer {

    @Inject
    Logger log;

    @Incoming("doc-authorize-in")
    public Uni<Void> process(DocumentEvent event) {
        log.infof("AuthorizeConsumer: processing documentId=%s, tenant=%s", event.documentId(), event.tenantSchemaName());
        // TODO: T-013 — Implementar polling de autorización SOAP
        return Uni.createFrom().voidItem();
    }
}
