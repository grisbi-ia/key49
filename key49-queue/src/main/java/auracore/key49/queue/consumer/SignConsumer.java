package auracore.key49.queue.consumer;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import auracore.key49.queue.event.DocumentEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Consumidor que firma documentos: genera XML, clave de acceso y firma XAdES-BES.
 * Transición: CREATED → SIGNED (éxito) o CREATED → FAILED (error irrecuperable).
 */
@ApplicationScoped
public class SignConsumer {

    @Inject
    Logger log;

    @Incoming("doc-sign-in")
    public Uni<Void> process(DocumentEvent event) {
        log.infof("SignConsumer: processing documentId=%s, tenant=%s", event.documentId(), event.tenantSchemaName());
        // TODO: T-013 — Implementar pipeline: generar XML + clave acceso + firma XAdES-BES
        return Uni.createFrom().voidItem();
    }
}
